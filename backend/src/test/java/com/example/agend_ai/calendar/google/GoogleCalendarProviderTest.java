package com.example.agend_ai.calendar.google;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.agend_ai.calendar.OAuthGrant;
import com.example.agend_ai.calendar.RemoteEvent;
import com.example.agend_ai.calendar.RemoteEventDraft;
import com.example.agend_ai.config.GoogleCalendarProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GoogleCalendarProviderTest {
  private WireMockServer wire;
  private GoogleCalendarProvider provider;

  @BeforeEach
  void start() {
    wire = new WireMockServer(0);
    wire.start();
    provider = new GoogleCalendarProvider(properties(), new ObjectMapper());
  }

  @AfterEach
  void stop() {
    wire.stop();
  }

  @Test
  void authorizationUrlRequestsOfflineAccessAndNarrowCalendarScopes() {
    String url = provider.authorizationUrl("state-123");
    Map<String, String> query = query(url);
    assertThat(query.get("client_id")).isEqualTo("client-id");
    assertThat(query.get("redirect_uri")).isEqualTo("http://localhost:5173/api/calendar/google/callback");
    assertThat(query.get("response_type")).isEqualTo("code");
    assertThat(query.get("access_type")).isEqualTo("offline");
    assertThat(query.get("prompt")).isEqualTo("consent");
    assertThat(query.get("state")).isEqualTo("state-123");
    assertThat(query.get("scope"))
        .contains("https://www.googleapis.com/auth/calendar.events")
        .contains("https://www.googleapis.com/auth/calendar.calendarlist.readonly");
  }

  @Test
  void exchangesAuthorizationCodeUsingFormBody() {
    wire.stubFor(
        post(urlEqualTo("/token"))
            .willReturn(okJson("{\"access_token\":\"access\",\"refresh_token\":\"refresh\",\"expires_in\":3600,\"scope\":\"calendar\"}")));

    OAuthGrant grant = provider.exchangeCode("code-1");

    assertThat(grant.accessToken()).isEqualTo("access");
    assertThat(grant.refreshToken()).isEqualTo("refresh");
    wire.verify(
        postRequestedFor(urlEqualTo("/token"))
            .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
            .withRequestBody(containing("grant_type=authorization_code"))
            .withRequestBody(containing("code=code-1")));
  }

  @Test
  void parsesCalendarListAndEventsIncludingAllDay() {
    wire.stubFor(
        get(urlPathEqualTo("/calendar/v3/users/me/calendarList"))
            .willReturn(
                okJson(
                    "{\"items\":[{\"id\":\"primary@example.com\",\"summary\":\"Личный\",\"timeZone\":\"Europe/Moscow\",\"accessRole\":\"owner\",\"primary\":true}]}")));
    wire.stubFor(
        get(urlPathEqualTo("/calendar/v3/calendars/primary%40example.com/events"))
            .willReturn(
                okJson(
                    "{\"items\":["
                        + "{\"id\":\"timed\",\"status\":\"confirmed\",\"summary\":\"Встреча\",\"start\":{\"dateTime\":\"2026-11-05T10:00:00+03:00\",\"timeZone\":\"Europe/Moscow\"},\"end\":{\"dateTime\":\"2026-11-05T11:00:00+03:00\"}},"
                        + "{\"id\":\"all-day\",\"status\":\"confirmed\",\"summary\":\"Выходной\",\"start\":{\"date\":\"2026-11-06\"},\"end\":{\"date\":\"2026-11-07\"}}"
                        + "],\"nextSyncToken\":\"sync-1\"}")));

    var calendars = provider.listCalendars("token");
    var page =
        provider.listEvents(
            "token",
            "primary@example.com",
            null,
            null,
            Instant.parse("2026-11-01T00:00:00Z"),
            Instant.parse("2026-12-01T00:00:00Z"));

    assertThat(calendars).hasSize(1);
    assertThat(calendars.get(0).primary()).isTrue();
    assertThat(calendars.get(0).accessRole()).isEqualTo("owner");
    assertThat(page.nextSyncToken()).isEqualTo("sync-1");
    assertThat(page.events()).hasSize(2);
    assertThat(page.events().get(0).allDay()).isFalse();
    assertThat(page.events().get(0).startAt()).isEqualTo(Instant.parse("2026-11-05T07:00:00Z"));
    assertThat(page.events().get(1).allDay()).isTrue();
    assertThat(page.events().get(1).allDayStart().toString()).isEqualTo("2026-11-06");
  }

  @Test
  void updateUsesPatchAndPreservesAgendaiPrivateId() {
    wire.stubFor(
        patch(urlPathEqualTo("/calendar/v3/calendars/primary%40example.com/events/event-1"))
            .willReturn(
                okJson(
                    "{\"id\":\"event-1\",\"etag\":\"etag-2\",\"status\":\"confirmed\",\"summary\":\"Новая встреча\",\"start\":{\"dateTime\":\"2026-11-05T07:00:00Z\"},\"end\":{\"dateTime\":\"2026-11-05T08:00:00Z\"},\"extendedProperties\":{\"private\":{\"agendaiItemId\":\"00000000-0000-0000-0000-000000000123\"}}}")));

    RemoteEvent result =
        provider.updateEvent(
            "token",
            "primary@example.com",
            "event-1",
            new RemoteEventDraft(
                "Новая встреча",
                "Описание",
                "Переговорная",
                Instant.parse("2026-11-05T07:00:00Z"),
                Instant.parse("2026-11-05T08:00:00Z"),
                null,
                null,
                "Europe/Moscow",
                "00000000-0000-0000-0000-000000000123"),
            "etag-1");

    assertThat(result.etag()).isEqualTo("etag-2");
    wire.verify(
        patchRequestedFor(urlPathEqualTo("/calendar/v3/calendars/primary%40example.com/events/event-1"))
            .withHeader("Authorization", equalTo("Bearer token"))
            .withHeader("If-Match", equalTo("etag-1"))
            .withRequestBody(matchingJsonPath("$.extendedProperties.private.agendaiItemId", equalTo("00000000-0000-0000-0000-000000000123"))));
  }

  private GoogleCalendarProperties properties() {
    return new GoogleCalendarProperties(
        "client-id",
        "client-secret",
        "http://localhost:5173/api/calendar/google/callback",
        "http://localhost:5173/",
        wire.baseUrl() + "/authorize",
        wire.baseUrl() + "/token",
        wire.baseUrl() + "/revoke",
        wire.baseUrl() + "/calendar/v3",
        "",
        3,
        10,
        60_000,
        3_000,
        90,
        365,
        24,
        604800);
  }

  private static Map<String, String> query(String url) {
    String raw = URI.create(url).getRawQuery();
    return Arrays.stream(raw.split("&"))
        .map(part -> part.split("=", 2))
        .collect(
            Collectors.toMap(
                pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                pair -> pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : ""));
  }
}
