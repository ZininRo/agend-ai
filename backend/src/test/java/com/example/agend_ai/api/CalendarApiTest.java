package com.example.agend_ai.api;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.agend_ai.calendar.CalendarOutboxService;
import com.example.agend_ai.calendar.CalendarSyncWorker;
import com.example.agend_ai.common.DefaultUser;
import com.example.agend_ai.entity.Item;
import com.example.agend_ai.entity.enums.ItemSource;
import com.example.agend_ai.entity.enums.ItemType;
import com.example.agend_ai.repository.CalendarConnectionRepository;
import com.example.agend_ai.repository.CalendarOAuthStateRepository;
import com.example.agend_ai.repository.CalendarSyncOutboxRepository;
import com.example.agend_ai.repository.ExternalCalendarRepository;
import com.example.agend_ai.repository.ItemCalendarLinkRepository;
import com.example.agend_ai.repository.ItemRepository;
import com.example.agend_ai.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(CalendarApiTest.FixedTime.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CalendarApiTest extends DatabaseTestSupport {
  static final WireMockServer wire = new WireMockServer(0);
  static { wire.start(); }

  @TestConfiguration
  static class FixedTime {
    @Bean
    @Primary
    Clock fixedClock() {
      return Clock.fixed(Instant.parse("2026-11-04T12:00:00Z"), ZoneOffset.UTC);
    }
  }

  @DynamicPropertySource
  static void calendar(DynamicPropertyRegistry r) {
    r.add("calendar.google.client-id", () -> "client-id");
    r.add("calendar.google.client-secret", () -> "client-secret");
    r.add("calendar.google.authorization-url", () -> wire.baseUrl() + "/authorize");
    r.add("calendar.google.token-url", () -> wire.baseUrl() + "/token");
    r.add("calendar.google.revoke-url", () -> wire.baseUrl() + "/revoke");
    r.add("calendar.google.api-base-url", () -> wire.baseUrl() + "/calendar/v3");
    r.add("calendar.google.redirect-uri", () -> "http://localhost:5173/api/calendar/google/callback");
    r.add("calendar.google.frontend-return-url", () -> "http://localhost:5173/");
    r.add("calendar.google.poll-delay-ms", () -> 3_600_000);
    r.add("calendar.google.outbox-delay-ms", () -> 3_600_000);
  }

  @AfterAll
  static void stop() { wire.stop(); }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired ItemRepository items;
  @Autowired UserRepository users;
  @Autowired ItemCalendarLinkRepository links;
  @Autowired CalendarSyncOutboxRepository outboxRows;
  @Autowired ExternalCalendarRepository calendars;
  @Autowired CalendarConnectionRepository connections;
  @Autowired CalendarOAuthStateRepository states;
  @Autowired CalendarOutboxService outbox;
  @Autowired CalendarSyncWorker worker;

  @BeforeEach
  void clean() {
    wire.resetAll();
    outboxRows.deleteAllInBatch();
    links.deleteAllInBatch();
    calendars.deleteAllInBatch();
    connections.deleteAllInBatch();
    states.deleteAllInBatch();
    items.deleteAllInBatch();
  }

  @Test
  void oauthConnectAndManualSyncImportsGoogleEvent() throws Exception {
    mvc.perform(get("/api/calendar/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.configured").value(true))
        .andExpect(jsonPath("$.connected").value(false));

    connectGoogle();

    mvc.perform(get("/api/calendar/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.connected").value(true))
        .andExpect(jsonPath("$.calendars.length()").value(1))
        .andExpect(jsonPath("$.calendars[0].name").value("Основной"))
        .andExpect(jsonPath("$.calendars[0].sync_enabled").value(true))
        .andExpect(jsonPath("$.calendars[0].write_target").value(true));

    wire.stubFor(
        WireMock.get(urlPathEqualTo("/calendar/v3/calendars/primary%40example.com/events"))
            .willReturn(
                okJson(
                    "{\"items\":[{\"id\":\"google-event-1\",\"etag\":\"e1\",\"status\":\"confirmed\",\"summary\":\"Встреча из Google\",\"location\":\"Zoom\",\"updated\":\"2026-11-04T10:00:00Z\",\"start\":{\"dateTime\":\"2026-11-05T10:00:00+03:00\",\"timeZone\":\"Europe/Moscow\"},\"end\":{\"dateTime\":\"2026-11-05T11:00:00+03:00\"}}],\"nextSyncToken\":\"sync-1\"}")));

    mvc.perform(post("/api/calendar/sync"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.calendars_synced").value(1))
        .andExpect(jsonPath("$.events_applied").value(1));

    Item imported = items.findAll().stream().filter(i -> "Встреча из Google".equals(i.getTitle())).findFirst().orElseThrow();
    assertThat(imported.getSource()).isEqualTo(ItemSource.GOOGLE_CALENDAR);
    assertThat(imported.getType()).isEqualTo(ItemType.MEETING);
    assertThat(imported.getPlace()).isEqualTo("Zoom");
    assertThat(links.findByItem_Id(imported.getId())).isPresent();
  }

  @Test
  void localMeetingIsExportedToSelectedGoogleCalendarThroughOutbox() throws Exception {
    connectGoogle();

    Item item = new Item();
    item.setUser(users.getReferenceById(DefaultUser.ID));
    item.setType(ItemType.MEETING);
    item.setTitle("Встреча из AgendAI");
    item.setStartAt(Instant.parse("2026-11-06T09:00:00Z"));
    item.setEndAt(Instant.parse("2026-11-06T10:00:00Z"));
    item = items.saveAndFlush(item);
    outbox.enqueue(item);

    wire.stubFor(
        WireMock.post(urlPathEqualTo("/calendar/v3/calendars/primary%40example.com/events"))
            .willReturn(
                okJson(
                    "{\"id\":\"created-1\",\"etag\":\"etag-created\",\"status\":\"confirmed\",\"summary\":\"Встреча из AgendAI\",\"start\":{\"dateTime\":\"2026-11-06T09:00:00Z\"},\"end\":{\"dateTime\":\"2026-11-06T10:00:00Z\"},\"extendedProperties\":{\"private\":{\"agendaiItemId\":\""
                        + item.getId()
                        + "\"}}}")));

    assertThat(worker.processOutboxBatch(10)).isEqualTo(1);
    assertThat(outboxRows.findById(item.getId())).isEmpty();
    assertThat(links.findByItem_Id(item.getId())).isPresent();
    wire.verify(
        postRequestedFor(urlPathEqualTo("/calendar/v3/calendars/primary%40example.com/events"))
            .withRequestBody(matchingJsonPath("$.summary", equalTo("Встреча из AgendAI")))
            .withRequestBody(matchingJsonPath("$.extendedProperties.private.agendaiItemId", equalTo(item.getId().toString()))));
  }

  private void connectGoogle() throws Exception {
    var result =
        mvc.perform(post("/api/calendar/google/connect"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authorization_url").isNotEmpty())
            .andReturn();
    JsonNode json = mapper.readTree(result.getResponse().getContentAsByteArray());
    String state = queryParam(json.path("authorization_url").asText(), "state");

    wire.stubFor(
        WireMock.post(urlEqualTo("/token"))
            .willReturn(okJson("{\"access_token\":\"access-token\",\"refresh_token\":\"refresh-token\",\"expires_in\":3600,\"scope\":\"calendar\"}")));
    wire.stubFor(
        WireMock.get(urlPathEqualTo("/calendar/v3/users/me/calendarList"))
            .willReturn(
                okJson(
                    "{\"items\":[{\"id\":\"primary@example.com\",\"summary\":\"Основной\",\"timeZone\":\"Europe/Moscow\",\"accessRole\":\"owner\",\"primary\":true}]}")));

    mvc.perform(get("/api/calendar/google/callback").param("code", "code-1").param("state", state))
        .andExpect(status().isFound())
        .andExpect(header().string(HttpHeaders.LOCATION, "http://localhost:5173/?calendar=connected"));
  }

  private static String queryParam(String url, String name) {
    String raw = URI.create(url).getRawQuery();
    for (String pair : raw.split("&")) {
      String[] parts = pair.split("=", 2);
      String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
      if (name.equals(key)) {
        return parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
      }
    }
    throw new IllegalArgumentException("Missing query param " + name);
  }
}
