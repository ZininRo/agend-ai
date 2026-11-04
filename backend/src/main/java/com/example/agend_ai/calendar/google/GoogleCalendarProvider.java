package com.example.agend_ai.calendar.google;

import com.example.agend_ai.calendar.CalendarProvider;
import com.example.agend_ai.calendar.OAuthGrant;
import com.example.agend_ai.calendar.RemoteCalendar;
import com.example.agend_ai.calendar.RemoteEvent;
import com.example.agend_ai.calendar.RemoteEventDraft;
import com.example.agend_ai.calendar.RemoteEventPage;
import com.example.agend_ai.calendar.WatchRegistration;
import com.example.agend_ai.config.GoogleCalendarProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

@Slf4j
@Component
public class GoogleCalendarProvider implements CalendarProvider {
  public static final String PROVIDER = "GOOGLE";
  private static final List<String> SCOPES =
      List.of(
          "https://www.googleapis.com/auth/calendar.events",
          "https://www.googleapis.com/auth/calendar.calendarlist.readonly");

  private final GoogleCalendarProperties props;
  private final ObjectMapper mapper;
  private final RestClient api;
  private final RestClient raw;

  public GoogleCalendarProvider(GoogleCalendarProperties props, ObjectMapper mapper) {
    this.props = props;
    this.mapper = mapper;
    HttpClient httpClient =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(Math.min(5, props.timeoutSec())))
            .build();
    var factory = new JdkClientHttpRequestFactory(httpClient);
    factory.setReadTimeout(Duration.ofSeconds(props.timeoutSec()));
    this.api = RestClient.builder().baseUrl(props.apiBaseUrl()).requestFactory(factory).build();
    this.raw = RestClient.builder().requestFactory(factory).build();
  }

  @Override
  public String providerId() {
    return PROVIDER;
  }

  @Override
  public boolean configured() {
    return props.configured();
  }

  @Override
  public String authorizationUrl(String state) {
    if (!configured()) throw new GoogleApiException(503);
    return UriComponentsBuilder.fromUriString(props.authorizationUrl())
        .queryParam("client_id", props.clientId())
        .queryParam("redirect_uri", props.redirectUri())
        .queryParam("response_type", "code")
        .queryParam("scope", String.join(" ", SCOPES))
        .queryParam("access_type", "offline")
        .queryParam("include_granted_scopes", "true")
        .queryParam("prompt", "consent")
        .queryParam("state", state)
        .build()
        .encode()
        .toUriString();
  }

  @Override
  public OAuthGrant exchangeCode(String code) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("code", code);
    form.add("client_id", props.clientId());
    form.add("client_secret", props.clientSecret());
    form.add("redirect_uri", props.redirectUri());
    form.add("grant_type", "authorization_code");
    return token(form);
  }

  @Override
  public OAuthGrant refreshAccessToken(String refreshToken) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("client_id", props.clientId());
    form.add("client_secret", props.clientSecret());
    form.add("refresh_token", refreshToken);
    form.add("grant_type", "refresh_token");
    return token(form);
  }

  private OAuthGrant token(MultiValueMap<String, String> form) {
    try {
      JsonNode json =
          raw.post()
              .uri(props.tokenUrl())
              .contentType(MediaType.APPLICATION_FORM_URLENCODED)
              .accept(MediaType.APPLICATION_JSON)
              .body(form)
              .retrieve()
              .body(JsonNode.class);
      if (json == null || json.path("access_token").asText().isBlank()) throw new GoogleApiException(502);
      String refresh = json.path("refresh_token").isTextual() ? json.path("refresh_token").asText() : null;
      return new OAuthGrant(
          json.path("access_token").asText(),
          refresh,
          json.path("expires_in").asLong(3600),
          json.path("scope").asText(null));
    } catch (RestClientResponseException e) {
      log.warn("Google OAuth token endpoint returned HTTP {}", e.getStatusCode().value());
      throw new GoogleApiException(e.getStatusCode().value());
    } catch (RestClientException e) {
      log.warn("Google OAuth token endpoint unavailable: {}", e.getClass().getSimpleName());
      throw new GoogleApiException(503);
    }
  }

  @Override
  public List<RemoteCalendar> listCalendars(String accessToken) {
    List<RemoteCalendar> result = new ArrayList<>();
    String pageToken = null;
    do {
      final String next = pageToken;
      JsonNode json =
          get(
              accessToken,
              uri -> {
                uri.path("/users/me/calendarList").queryParam("maxResults", 250);
                if (next != null) uri.queryParam("pageToken", next);
              });
      for (JsonNode item : json.path("items")) {
        if (item.path("deleted").asBoolean(false)) continue;
        String id = item.path("id").asText(null);
        if (id == null || id.isBlank()) continue;
        result.add(
            new RemoteCalendar(
                id,
                item.path("summaryOverride").asText(item.path("summary").asText("Календарь")),
                item.path("timeZone").asText(null),
                item.path("accessRole").asText("reader"),
                item.path("primary").asBoolean(false)));
      }
      pageToken = textOrNull(json, "nextPageToken");
    } while (pageToken != null);
    return result;
  }

  @Override
  public RemoteEventPage listEvents(
      String accessToken,
      String calendarId,
      String syncToken,
      String pageToken,
      Instant timeMin,
      Instant timeMax) {
    try {
      JsonNode json =
          api.get()
              .uri(
                  uri -> {
                    uri.path("/calendars/{calendarId}/events")
                        .queryParam("maxResults", 2500)
                        .queryParam("singleEvents", true)
                        .queryParam("showDeleted", true);
                    if (syncToken != null && !syncToken.isBlank()) {
                      uri.queryParam("syncToken", syncToken);
                    } else {
                      if (timeMin != null) uri.queryParam("timeMin", timeMin.toString());
                      if (timeMax != null) uri.queryParam("timeMax", timeMax.toString());
                    }
                    if (pageToken != null) uri.queryParam("pageToken", pageToken);
                    return uri.build(calendarId);
                  })
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
              .accept(MediaType.APPLICATION_JSON)
              .retrieve()
              .body(JsonNode.class);
      if (json == null) throw new GoogleApiException(502);
      List<RemoteEvent> events = new ArrayList<>();
      for (JsonNode item : json.path("items")) {
        RemoteEvent event = parseEvent(item);
        if (event != null) events.add(event);
      }
      return new RemoteEventPage(events, textOrNull(json, "nextPageToken"), textOrNull(json, "nextSyncToken"));
    } catch (RestClientResponseException e) {
      throw apiFailure("events.list", e);
    } catch (RestClientException e) {
      throw transportFailure("events.list", e);
    }
  }

  @Override
  public RemoteEvent createEvent(String accessToken, String calendarId, RemoteEventDraft draft) {
    try {
      JsonNode json =
          api.post()
              .uri(uri -> uri.path("/calendars/{calendarId}/events").build(calendarId))
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
              .contentType(MediaType.APPLICATION_JSON)
              .accept(MediaType.APPLICATION_JSON)
              .body(eventBody(draft))
              .retrieve()
              .body(JsonNode.class);
      RemoteEvent event = json == null ? null : parseEvent(json);
      if (event == null) throw new GoogleApiException(502);
      return event;
    } catch (RestClientResponseException e) {
      throw apiFailure("events.insert", e);
    } catch (RestClientException e) {
      throw transportFailure("events.insert", e);
    }
  }

  @Override
  public RemoteEvent updateEvent(
      String accessToken, String calendarId, String eventId, RemoteEventDraft draft, String etag) {
    try {
      RestClient.RequestBodySpec request =
          api.patch()
              .uri(
                  uri ->
                      uri.path("/calendars/{calendarId}/events/{eventId}")
                          .build(calendarId, eventId))
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
              .contentType(MediaType.APPLICATION_JSON)
              .accept(MediaType.APPLICATION_JSON);
      if (etag != null && !etag.isBlank()) request.header(HttpHeaders.IF_MATCH, etag);
      JsonNode json = request.body(eventBody(draft)).retrieve().body(JsonNode.class);
      RemoteEvent event = json == null ? null : parseEvent(json);
      if (event == null) throw new GoogleApiException(502);
      return event;
    } catch (RestClientResponseException e) {
      throw apiFailure("events.patch", e);
    } catch (RestClientException e) {
      throw transportFailure("events.patch", e);
    }
  }

  @Override
  public void deleteEvent(String accessToken, String calendarId, String eventId) {
    try {
      api.delete()
          .uri(
              uri ->
                  uri.path("/calendars/{calendarId}/events/{eventId}")
                      .build(calendarId, eventId))
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientResponseException e) {
      int status = e.getStatusCode().value();
      if (status == 404 || status == 410) return;
      throw apiFailure("events.delete", e);
    } catch (RestClientException e) {
      throw transportFailure("events.delete", e);
    }
  }

  @Override
  public WatchRegistration watchEvents(
      String accessToken,
      String calendarId,
      String channelId,
      String callbackUrl,
      String channelToken) {
    ObjectNode body = mapper.createObjectNode();
    body.put("id", channelId);
    body.put("type", "web_hook");
    body.put("address", callbackUrl);
    body.put("token", channelToken);
    body.putObject("params").put("ttl", Integer.toString(props.watchTtlSeconds()));
    try {
      JsonNode json =
          api.post()
              .uri(uri -> uri.path("/calendars/{calendarId}/events/watch").build(calendarId))
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
              .contentType(MediaType.APPLICATION_JSON)
              .accept(MediaType.APPLICATION_JSON)
              .body(body)
              .retrieve()
              .body(JsonNode.class);
      if (json == null || json.path("resourceId").asText().isBlank()) throw new GoogleApiException(502);
      long expiration = json.path("expiration").asLong(0L);
      return new WatchRegistration(
          json.path("id").asText(channelId),
          json.path("resourceId").asText(),
          expiration > 0 ? Instant.ofEpochMilli(expiration) : null);
    } catch (RestClientResponseException e) {
      throw apiFailure("events.watch", e);
    } catch (RestClientException e) {
      throw transportFailure("events.watch", e);
    }
  }

  @Override
  public void stopWatch(String accessToken, String channelId, String resourceId) {
    if (channelId == null || resourceId == null) return;
    ObjectNode body = mapper.createObjectNode();
    body.put("id", channelId);
    body.put("resourceId", resourceId);
    try {
      api.post()
          .uri("/channels/stop")
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
          .contentType(MediaType.APPLICATION_JSON)
          .body(body)
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() != 404) log.debug("Google channels.stop HTTP {}", e.getStatusCode().value());
    } catch (RestClientException ignored) {
      // Best effort: expired channels stop by themselves.
    }
  }

  @Override
  public void revokeToken(String token) {
    if (token == null || token.isBlank()) return;
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("token", token);
    try {
      raw.post()
          .uri(props.revokeUrl())
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .body(form)
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientException ignored) {
      // Disconnect locally even when Google revoke endpoint is temporarily unavailable.
    }
  }

  private JsonNode get(String accessToken, java.util.function.Consumer<org.springframework.web.util.UriBuilder> customize) {
    try {
      return api.get()
          .uri(
              uri -> {
                customize.accept(uri);
                return uri.build();
              })
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
          .accept(MediaType.APPLICATION_JSON)
          .retrieve()
          .body(JsonNode.class);
    } catch (RestClientResponseException e) {
      throw apiFailure("calendarList.list", e);
    } catch (RestClientException e) {
      throw transportFailure("calendarList.list", e);
    }
  }

  private ObjectNode eventBody(RemoteEventDraft draft) {
    ObjectNode body = mapper.createObjectNode();
    body.put("summary", blankToDefault(draft.summary(), "Без названия"));
    if (draft.description() != null && !draft.description().isBlank()) body.put("description", draft.description());
    if (draft.location() != null && !draft.location().isBlank()) body.put("location", draft.location());
    ObjectNode start = body.putObject("start");
    ObjectNode end = body.putObject("end");
    if (draft.allDay()) {
      start.put("date", draft.allDayStart().toString());
      end.put("date", draft.allDayEndExclusive().toString());
    } else {
      start.put("dateTime", draft.startAt().toString());
      end.put("dateTime", draft.endAt().toString());
      if (draft.timezone() != null && !draft.timezone().isBlank()) {
        start.put("timeZone", draft.timezone());
        end.put("timeZone", draft.timezone());
      }
    }
    if (draft.agendaiItemId() != null) {
      body.putObject("extendedProperties").putObject("private").put("agendaiItemId", draft.agendaiItemId());
    }
    return body;
  }

  private static RemoteEvent parseEvent(JsonNode item) {
    String id = item.path("id").asText(null);
    if (id == null || id.isBlank()) return null;
    JsonNode start = item.path("start");
    JsonNode end = item.path("end");
    Instant startAt = instantOrNull(start.path("dateTime").asText(null));
    Instant endAt = instantOrNull(end.path("dateTime").asText(null));
    LocalDate allDayStart = dateOrNull(start.path("date").asText(null));
    LocalDate allDayEnd = dateOrNull(end.path("date").asText(null));
    String timezone = start.path("timeZone").asText(end.path("timeZone").asText(null));
    return new RemoteEvent(
        id,
        item.path("etag").asText(null),
        item.path("status").asText("confirmed"),
        item.path("summary").asText("Без названия"),
        item.path("description").isTextual() ? item.path("description").asText() : null,
        item.path("location").isTextual() ? item.path("location").asText() : null,
        startAt,
        endAt,
        allDayStart,
        allDayEnd,
        timezone,
        instantOrNull(item.path("updated").asText(null)),
        item.path("extendedProperties").path("private").path("agendaiItemId").asText(null));
  }

  private static Instant instantOrNull(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return Instant.parse(value);
    } catch (RuntimeException first) {
      try {
        return java.time.OffsetDateTime.parse(value).toInstant();
      } catch (RuntimeException ignored) {
        return null;
      }
    }
  }

  private static LocalDate dateOrNull(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return LocalDate.parse(value);
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private static String textOrNull(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
  }

  private static String blankToDefault(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private static GoogleApiException apiFailure(String operation, RestClientResponseException e) {
    log.warn("Google Calendar {} returned HTTP {}", operation, e.getStatusCode().value());
    return new GoogleApiException(e.getStatusCode().value());
  }

  private static GoogleApiException transportFailure(String operation, RestClientException e) {
    log.warn("Google Calendar {} unavailable: {}", operation, e.getClass().getSimpleName());
    return new GoogleApiException(503);
  }
}
