package com.example.agend_ai.calendar;

import com.example.agend_ai.api.error.AppException;
import com.example.agend_ai.api.error.ErrorCode;
import com.example.agend_ai.calendar.google.GoogleApiException;
import com.example.agend_ai.entity.CalendarConnection;
import com.example.agend_ai.repository.CalendarConnectionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CalendarCredentialService {
  private final CalendarProvider provider;
  private final CalendarConnectionRepository connections;
  private final Clock clock;

  @Transactional
  public String accessToken(CalendarConnection connection) {
    if (!"CONNECTED".equals(connection.getStatus()) || !connection.isSyncEnabled()) {
      throw new AppException(ErrorCode.CALENDAR_REAUTH_REQUIRED);
    }
    Instant now = clock.instant();
    if (connection.getAccessToken() != null
        && !connection.getAccessToken().isBlank()
        && connection.getAccessTokenExpiresAt() != null
        && connection.getAccessTokenExpiresAt().isAfter(now.plusSeconds(60))) {
      return connection.getAccessToken();
    }
    String refresh = connection.getRefreshToken();
    if (refresh == null || refresh.isBlank()) {
      markReauth(connection, "missing_refresh_token");
      throw new AppException(ErrorCode.CALENDAR_REAUTH_REQUIRED);
    }
    try {
      OAuthGrant grant = provider.refreshAccessToken(refresh);
      connection.setAccessToken(grant.accessToken());
      connection.setAccessTokenExpiresAt(
          now.plus(Duration.ofSeconds(Math.max(60, grant.expiresInSeconds()))));
      if (grant.refreshToken() != null && !grant.refreshToken().isBlank()) {
        connection.setRefreshToken(grant.refreshToken());
      }
      if (grant.scopes() != null && !grant.scopes().isBlank()) {
        connection.setGrantedScopes(grant.scopes());
      }
      connection.setStatus("CONNECTED");
      connection.setLastError(null);
      connections.save(connection);
      return connection.getAccessToken();
    } catch (GoogleApiException e) {
      if (e.status() == 400 || e.status() == 401 || e.status() == 403) {
        markReauth(connection, "oauth_refresh_http_" + e.status());
        throw new AppException(ErrorCode.CALENDAR_REAUTH_REQUIRED);
      }
      connection.setLastError("oauth_refresh_http_" + e.status());
      connections.save(connection);
      throw new AppException(ErrorCode.CALENDAR_UNAVAILABLE);
    }
  }

  @Transactional
  public void markReauth(CalendarConnection connection, String reason) {
    connection.setStatus("REAUTH_REQUIRED");
    connection.setLastError(reason);
    connections.save(connection);
  }
}
