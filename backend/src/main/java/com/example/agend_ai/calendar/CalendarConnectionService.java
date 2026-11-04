package com.example.agend_ai.calendar;

import com.example.agend_ai.api.error.AppException;
import com.example.agend_ai.api.error.ErrorCode;
import com.example.agend_ai.calendar.dto.CalendarConnectResponse;
import com.example.agend_ai.calendar.dto.CalendarSelectionRequest;
import com.example.agend_ai.calendar.dto.CalendarStatusDto;
import com.example.agend_ai.calendar.dto.ExternalCalendarDto;
import com.example.agend_ai.calendar.google.GoogleApiException;
import com.example.agend_ai.config.GoogleCalendarProperties;
import com.example.agend_ai.entity.CalendarConnection;
import com.example.agend_ai.entity.CalendarOAuthState;
import com.example.agend_ai.entity.ExternalCalendar;
import com.example.agend_ai.repository.CalendarConnectionRepository;
import com.example.agend_ai.repository.CalendarOAuthStateRepository;
import com.example.agend_ai.repository.ExternalCalendarRepository;
import com.example.agend_ai.repository.UserRepository;
import com.example.agend_ai.security.CurrentUser;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

@Service
@RequiredArgsConstructor
public class CalendarConnectionService {
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String GOOGLE = "GOOGLE";

  private final CalendarProvider provider;
  private final GoogleCalendarProperties properties;
  private final CalendarConnectionRepository connections;
  private final ExternalCalendarRepository calendars;
  private final CalendarOAuthStateRepository oauthStates;
  private final UserRepository users;
  private final CurrentUser currentUser;
  private final CalendarCredentialService credentials;
  private final CalendarOutboxService outbox;
  private final Clock clock;

  @Transactional(readOnly = true)
  public CalendarStatusDto status() {
    UUID userId = currentUser.id();
    CalendarConnection connection = connections.findByUser_IdAndProvider(userId, GOOGLE).orElse(null);
    if (connection == null) {
      return new CalendarStatusDto(provider.configured(), false, GOOGLE, "DISCONNECTED", null, null, List.of());
    }
    boolean connected = "CONNECTED".equals(connection.getStatus()) && connection.isSyncEnabled();
    List<ExternalCalendarDto> list =
        connected
            ? calendars.findAllByConnection_Id(connection.getId()).stream()
                .filter(ExternalCalendar::isAvailable)
                .sorted(Comparator.comparing(ExternalCalendar::isPrimaryCalendar).reversed()
                    .thenComparing(c -> c.getSummary() == null ? "" : c.getSummary(), String.CASE_INSENSITIVE_ORDER))
                .map(ExternalCalendarDto::from)
                .toList()
            : List.of();
    return new CalendarStatusDto(
        provider.configured(),
        connected,
        GOOGLE,
        connection.getStatus(),
        connection.getLastSyncAt(),
        connection.getLastError(),
        list);
  }

  @Transactional
  public CalendarConnectResponse connectUrl() {
    if (!provider.configured()) throw new AppException(ErrorCode.CALENDAR_NOT_CONFIGURED);
    Instant now = clock.instant();
    oauthStates.deleteByExpiresAtBefore(now);
    String rawState = randomToken();
    CalendarOAuthState state = new CalendarOAuthState();
    state.setStateHash(CalendarHash.sha256(rawState));
    state.setUser(users.getReferenceById(currentUser.id()));
    state.setCreatedAt(now);
    state.setExpiresAt(now.plus(Duration.ofMinutes(properties.oauthStateMinutes())));
    oauthStates.save(state);
    return new CalendarConnectResponse(provider.authorizationUrl(rawState));
  }

  /** Called from Google's public callback. The OAuth state maps the callback back to an AgendAI user. */
  @Transactional
  public void completeOAuth(String code, String rawState) {
    if (!provider.configured()) throw new AppException(ErrorCode.CALENDAR_NOT_CONFIGURED);
    if (code == null || code.isBlank() || rawState == null || rawState.isBlank()) {
      throw new AppException(ErrorCode.CALENDAR_AUTH_FAILED);
    }
    Instant now = clock.instant();
    String stateHash = CalendarHash.sha256(rawState);
    CalendarOAuthState state =
        oauthStates
            .findByStateHashAndExpiresAtAfter(stateHash, now)
            .orElseThrow(() -> new AppException(ErrorCode.CALENDAR_AUTH_FAILED));
    UUID userId = state.getUser().getId();
    oauthStates.delete(state);

    OAuthGrant grant;
    try {
      grant = provider.exchangeCode(code);
    } catch (GoogleApiException e) {
      throw new AppException(ErrorCode.CALENDAR_AUTH_FAILED);
    }

    CalendarConnection connection =
        connections.findByUser_IdAndProvider(userId, GOOGLE).orElseGet(CalendarConnection::new);
    if (connection.getId() == null) {
      connection.setUser(users.getReferenceById(userId));
      connection.setProvider(GOOGLE);
    }
    String refresh = grant.refreshToken();
    if ((refresh == null || refresh.isBlank()) && connection.getRefreshToken() != null) {
      refresh = connection.getRefreshToken();
    }
    if (refresh == null || refresh.isBlank()) throw new AppException(ErrorCode.CALENDAR_AUTH_FAILED);
    connection.setRefreshToken(refresh);
    connection.setAccessToken(grant.accessToken());
    connection.setAccessTokenExpiresAt(now.plusSeconds(Math.max(60, grant.expiresInSeconds())));
    connection.setGrantedScopes(grant.scopes());
    connection.setStatus("CONNECTED");
    connection.setSyncEnabled(true);
    connection.setLastError(null);
    connection = connections.saveAndFlush(connection);

    refreshCalendars(connection, true);
    outbox.enqueueExisting(userId);
  }

  @Transactional
  public CalendarStatusDto refreshCalendarsForCurrentUser() {
    CalendarConnection connection = requireConnected(currentUser.id());
    refreshCalendars(connection, false);
    return status();
  }

  @Transactional
  public CalendarStatusDto updateCalendar(UUID calendarId, CalendarSelectionRequest request) {
    if (request == null || (request.syncEnabled() == null && request.writeTarget() == null)) {
      throw new AppException(ErrorCode.CALENDAR_SELECTION_INVALID);
    }
    UUID userId = currentUser.id();
    ExternalCalendar calendar =
        calendars
            .findByIdAndConnection_User_Id(calendarId, userId)
            .filter(ExternalCalendar::isAvailable)
            .orElseThrow(() -> new AppException(ErrorCode.CALENDAR_SELECTION_INVALID));
    UUID connectionId = calendar.getConnection().getId();
    boolean becameWriteTarget = false;

    if (Boolean.TRUE.equals(request.writeTarget())) {
      if (!calendar.writable()) throw new AppException(ErrorCode.CALENDAR_SELECTION_INVALID);
      calendars.clearWriteTarget(connectionId);
      calendar =
          calendars
              .findByIdAndConnection_User_Id(calendarId, userId)
              .orElseThrow(() -> new AppException(ErrorCode.CALENDAR_SELECTION_INVALID));
      calendar.setWriteTarget(true);
      calendar.setSyncEnabled(true);
      calendar.setSyncToken(null);
      calendar.setLastFullSyncAt(null);
      calendar.setSyncRequestedAt(clock.instant());
      becameWriteTarget = true;
    } else if (Boolean.FALSE.equals(request.writeTarget())) {
      calendar.setWriteTarget(false);
    }

    if (request.syncEnabled() != null) {
      boolean enabled = request.syncEnabled();
      calendar.setSyncEnabled(enabled);
      if (!enabled) {
        calendar.setWriteTarget(false);
        calendar.setSyncRequestedAt(null);
      } else {
        calendar.setSyncToken(null);
        calendar.setLastFullSyncAt(null);
        calendar.setSyncRequestedAt(clock.instant());
      }
    }
    calendars.saveAndFlush(calendar);
    if (becameWriteTarget) outbox.enqueueExisting(userId);
    return status();
  }

  @Transactional
  public void disconnect() {
    UUID userId = currentUser.id();
    CalendarConnection connection = connections.findByUser_IdAndProvider(userId, GOOGLE).orElse(null);
    if (connection == null) return;
    String tokenToRevoke =
        connection.getRefreshToken() != null ? connection.getRefreshToken() : connection.getAccessToken();
    provider.revokeToken(tokenToRevoke);

    for (ExternalCalendar calendar : calendars.findAllByConnection_Id(connection.getId())) {
      calendar.setSyncEnabled(false);
      calendar.setWriteTarget(false);
      calendar.setSyncToken(null);
      calendar.setLastFullSyncAt(null);
      calendar.setSyncRequestedAt(null);
      calendar.setWatchChannelId(null);
      calendar.setWatchResourceId(null);
      calendar.setWatchTokenHash(null);
      calendar.setWatchExpiresAt(null);
      calendars.save(calendar);
    }
    connection.setAccessToken(null);
    connection.setRefreshToken(null);
    connection.setAccessTokenExpiresAt(null);
    connection.setStatus("DISCONNECTED");
    connection.setSyncEnabled(false);
    connection.setLastError(null);
    connections.save(connection);
    outbox.clearForUser(userId);
  }

  public String callbackRedirect(String outcome) {
    String value = outcome == null || outcome.isBlank() ? "error" : outcome;
    return UriComponentsBuilder.fromUriString(properties.frontendReturnUrl())
        .queryParam("calendar", value)
        .build()
        .encode()
        .toUriString();
  }

  @Transactional(readOnly = true)
  public CalendarConnection requireConnected(UUID userId) {
    return connections
        .findByUser_IdAndProvider(userId, GOOGLE)
        .filter(c -> "CONNECTED".equals(c.getStatus()) && c.isSyncEnabled())
        .orElseThrow(() -> new AppException(ErrorCode.CALENDAR_REAUTH_REQUIRED));
  }

  private void refreshCalendars(CalendarConnection connection, boolean reconnecting) {
    String accessToken = credentials.accessToken(connection);
    List<RemoteCalendar> remote;
    try {
      remote = provider.listCalendars(accessToken);
    } catch (GoogleApiException e) {
      if (e.status() == 401 || e.status() == 403) {
        credentials.markReauth(connection, "calendar_list_http_" + e.status());
        throw new AppException(ErrorCode.CALENDAR_REAUTH_REQUIRED);
      }
      throw new AppException(ErrorCode.CALENDAR_UNAVAILABLE);
    }

    List<ExternalCalendar> before =
        calendars.findAllByConnection_Id(connection.getId());
    boolean hadEnabled = before.stream().anyMatch(ExternalCalendar::isSyncEnabled) && !reconnecting;
    calendars.markAllUnavailable(connection.getId());
    Instant now = clock.instant();
    List<ExternalCalendar> saved = new ArrayList<>();
    for (RemoteCalendar entry : remote) {
      String hash = CalendarHash.sha256(entry.id());
      ExternalCalendar calendar =
          calendars
              .findByConnection_IdAndProviderCalendarIdHash(connection.getId(), hash)
              .orElseGet(ExternalCalendar::new);
      boolean fresh = calendar.getId() == null;
      if (fresh) {
        calendar.setConnection(connection);
        calendar.setProviderCalendarId(entry.id());
        calendar.setProviderCalendarIdHash(hash);
      }
      calendar.setSummary(entry.summary());
      calendar.setTimezone(entry.timezone());
      calendar.setAccessRole(entry.accessRole());
      calendar.setPrimaryCalendar(entry.primary());
      calendar.setAvailable(true);
      if (fresh || reconnecting || !hadEnabled) {
        calendar.setSyncEnabled(entry.primary());
        calendar.setWriteTarget(false);
      }
      if (!calendar.writable()) calendar.setWriteTarget(false);
      if (calendar.isSyncEnabled()) calendar.setSyncRequestedAt(now);
      saved.add(calendars.save(calendar));
    }
    calendars.flush();

    boolean hasTarget = saved.stream().anyMatch(c -> c.isAvailable() && c.isWriteTarget() && c.writable());
    if (!hasTarget) {
      ExternalCalendar target =
          saved.stream()
              .filter(ExternalCalendar::writable)
              .filter(ExternalCalendar::isPrimaryCalendar)
              .findFirst()
              .orElseGet(
                  () ->
                      saved.stream()
                          .filter(ExternalCalendar::writable)
                          .min(Comparator.comparing(c -> c.getSummary() == null ? "" : c.getSummary()))
                          .orElse(null));
      if (target != null) {
        calendars.clearWriteTarget(connection.getId());
        target = calendars.findById(target.getId()).orElseThrow();
        target.setWriteTarget(true);
        target.setSyncEnabled(true);
        target.setSyncRequestedAt(now);
        calendars.save(target);
      }
    }
  }

  private static String randomToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
