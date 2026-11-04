package com.example.agend_ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "calendar.google")
public record GoogleCalendarProperties(
    @DefaultValue("") String clientId,
    @DefaultValue("") String clientSecret,
    @DefaultValue("http://localhost:5173/api/calendar/google/callback") String redirectUri,
    @DefaultValue("http://localhost:5173/") String frontendReturnUrl,
    @DefaultValue("https://accounts.google.com/o/oauth2/v2/auth") String authorizationUrl,
    @DefaultValue("https://oauth2.googleapis.com/token") String tokenUrl,
    @DefaultValue("https://oauth2.googleapis.com/revoke") String revokeUrl,
    @DefaultValue("https://www.googleapis.com/calendar/v3") String apiBaseUrl,
    @DefaultValue("") String webhookBaseUrl,
    @DefaultValue("10") int timeoutSec,
    @DefaultValue("10") int oauthStateMinutes,
    @DefaultValue("60000") long pollDelayMs,
    @DefaultValue("3000") long outboxDelayMs,
    @DefaultValue("90") int initialPastDays,
    @DefaultValue("365") int initialFutureDays,
    @DefaultValue("24") int fullSyncEveryHours,
    @DefaultValue("604800") int watchTtlSeconds) {

  public boolean configured() {
    return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
  }
}
