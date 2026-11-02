package com.example.agend_ai.api;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.agend_ai.common.DefaultUser;
import com.example.agend_ai.entity.Item;
import com.example.agend_ai.entity.enums.ItemStatus;
import com.example.agend_ai.entity.enums.ItemType;
import com.example.agend_ai.repository.ChatSessionRepository;
import com.example.agend_ai.repository.ItemRepository;
import com.example.agend_ai.repository.MessageRepository;
import com.example.agend_ai.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.web.servlet.MockMvc;

/** Data-driven DB guardrails for the NLU -> dispatcher -> persistence pipeline. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(NluDbScenarioTest.FixedTime.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NluDbScenarioTest extends DatabaseTestSupport {
  static final WireMockServer wire = new WireMockServer(0);

  static {
    wire.start();
  }

  @TestConfiguration
  static class FixedTime {
    @Bean
    @Primary
    Clock fixedClock() {
      return Clock.fixed(Instant.parse("2026-11-02T09:00:00Z"), ZoneOffset.UTC);
    }
  }

  @DynamicPropertySource
  static void llm(DynamicPropertyRegistry r) {
    r.add("llm.base-url", wire::baseUrl);
    r.add("llm.api-key", () -> "test-key");
    r.add("llm.folder-id", () -> "test-folder");
    r.add("llm.timeout-sec", () -> 1);
  }

  @AfterAll
  static void stop() {
    wire.stop();
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired ItemRepository items;
  @Autowired MessageRepository messages;
  @Autowired ChatSessionRepository sessions;
  @Autowired UserRepository users;

  @TestFactory
  Stream<DynamicTest> dbBackedScenarios() throws Exception {
    ScenarioFile file;
    try (InputStream in =
        getClass().getClassLoader().getResourceAsStream("nlu-evaluation/e2e-scenarios.json")) {
      if (in == null) throw new IllegalStateException("e2e-scenarios.json is missing");
      file = mapper.readValue(in, ScenarioFile.class);
    }
    return file.scenarios().stream()
        .map(s -> DynamicTest.dynamicTest(s.id() + " " + s.name(), () -> runScenario(s, file.timezone())));
  }

  private void runScenario(Scenario scenario, String timezone) throws Exception {
    messages.deleteAll();
    sessions.deleteAll();
    items.deleteAll();
    wire.resetAll();
    for (SeedItem seed : scenario.seedItems()) seed(seed);
    UUID sessionId = UUID.randomUUID();

    for (Step step : scenario.steps()) {
      wire.resetMappings();
      if (step.llm() != null && !step.llm().isNull()) stub(step.llm());
      var response =
          mvc.perform(
                  post("/api/message")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          mapper.writeValueAsBytes(
                              java.util.Map.of(
                                  "session_id", sessionId.toString(),
                                  "text", step.text(),
                                  "source", "text",
                                  "timezone", timezone))))
              .andExpect(status().isOk())
              .andReturn();
      JsonNode body = mapper.readTree(response.getResponse().getContentAsByteArray());
      Expect expect = step.expect();
      assertThat(body.path("result").asText()).isEqualTo(expect.result());
      assertThat(body.path("intent").asText()).isEqualTo(expect.intent());
      assertThat(body.path("data_changed").asBoolean()).isEqualTo(expect.dataChanged());
      assertThat(body.path("items").size()).isEqualTo(expect.responseItems());
      assertThat(items.findAllActiveByUserId(DefaultUser.ID)).hasSize(expect.activeDbItems());
    }

    List<Item> actual = new ArrayList<>(items.findAllActiveByUserId(DefaultUser.ID));
    actual.sort(Comparator.comparing(Item::getTitle));
    List<SeedItem> expected = new ArrayList<>(scenario.finalItems());
    expected.sort(Comparator.comparing(SeedItem::title));
    assertThat(actual).hasSize(expected.size());
    for (int i = 0; i < expected.size(); i++) assertItem(actual.get(i), expected.get(i));
  }

  private void seed(SeedItem seed) {
    Item item = new Item();
    item.setUser(users.getReferenceById(DefaultUser.ID));
    item.setType(ItemType.valueOf(seed.type()));
    item.setTitle(seed.title());
    item.setStatus(seed.status() == null ? ItemStatus.TODO : ItemStatus.valueOf(seed.status()));
    if (seed.startAt() != null) item.setStartAt(Instant.parse(seed.startAt()));
    if (seed.endAt() != null) item.setEndAt(Instant.parse(seed.endAt()));
    if (seed.dueAt() != null) item.setDueAt(Instant.parse(seed.dueAt()));
    items.saveAndFlush(item);
  }

  private void assertItem(Item actual, SeedItem expected) {
    assertThat(actual.getType().name()).isEqualTo(expected.type());
    assertThat(actual.getTitle()).isEqualTo(expected.title());
    assertThat(actual.getStatus().name()).isEqualTo(expected.status() == null ? "TODO" : expected.status());
    if (expected.startAt() != null) assertThat(actual.getStartAt()).isEqualTo(Instant.parse(expected.startAt()));
    if (expected.dueAt() != null) assertThat(actual.getDueAt()).isEqualTo(Instant.parse(expected.dueAt()));
    if (expected.endAt() != null) assertThat(actual.getEndAt()).isEqualTo(Instant.parse(expected.endAt()));
  }

  private void stub(JsonNode payload) throws Exception {
    String raw = mapper.writeValueAsString(payload);
    String envelope =
        mapper.writeValueAsString(
            java.util.Map.of(
                "result",
                java.util.Map.of(
                    "alternatives",
                    List.of(
                        java.util.Map.of(
                            "message", java.util.Map.of("role", "assistant", "text", raw),
                            "status", "ALTERNATIVE_STATUS_FINAL")))));
    wire.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock
            .post(urlEqualTo("/foundationModels/v1/completion"))
            .willReturn(okJson(envelope)));
  }

  record ScenarioFile(String version, String fixedNow, String timezone, List<Scenario> scenarios) {}

  record Scenario(
      String id,
      String name,
      List<SeedItem> seedItems,
      List<Step> steps,
      List<SeedItem> finalItems) {}

  record Step(String text, JsonNode llm, Expect expect) {}

  record Expect(
      String result,
      String intent,
      boolean dataChanged,
      int responseItems,
      int activeDbItems) {}

  record SeedItem(
      String type,
      String title,
      String status,
      String startAt,
      String endAt,
      String dueAt) {}
}
