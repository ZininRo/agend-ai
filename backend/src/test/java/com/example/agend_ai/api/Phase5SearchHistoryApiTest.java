package com.example.agend_ai.api;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.agend_ai.common.DefaultUser;
import com.example.agend_ai.entity.Item;
import com.example.agend_ai.entity.enums.*;
import com.example.agend_ai.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class Phase5SearchHistoryApiTest extends DatabaseTestSupport {
  static final WireMockServer wire = new WireMockServer(0);
  static { wire.start(); }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("llm.base-url", wire::baseUrl);
    r.add("llm.api-key", () -> "test-key");
    r.add("llm.folder-id", () -> "test-folder");
    r.add("limits.rate.message-per-min", () -> 100);
  }

  @AfterAll static void stop() { wire.stop(); }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired ItemRepository items;
  @Autowired MessageRepository messages;
  @Autowired ChatSessionRepository sessions;
  @Autowired UserRepository users;
  UUID session;

  @BeforeEach
  void clean() {
    messages.deleteAll(); sessions.deleteAll(); items.deleteAll(); wire.resetAll();
    session = UUID.randomUUID();
  }

  private Item seed(String title, ItemType type, String instant) {
    Item i = new Item();
    i.setUser(users.getReferenceById(DefaultUser.ID));
    i.setTitle(title); i.setType(type); i.setStatus(ItemStatus.TODO);
    if (type == ItemType.TASK) i.setDueAt(Instant.parse(instant)); else i.setStartAt(Instant.parse(instant));
    return items.saveAndFlush(i);
  }

  private String payload(String intent, Map<String, Object> slots) throws Exception {
    return mapper.writeValueAsString(Map.of("intent", intent, "confidence", 0.98, "slots", slots));
  }

  private void stub(String raw) throws Exception {
    String envelope = mapper.writeValueAsString(Map.of("result", Map.of("alternatives", List.of(
        Map.of("message", Map.of("role", "assistant", "text", raw), "status", "ALTERNATIVE_STATUS_FINAL")))));
    wire.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/foundationModels/v1/completion"))
        .willReturn(okJson(envelope)));
  }

  private org.springframework.test.web.servlet.ResultActions send(String text) throws Exception {
    return mvc.perform(post("/api/message").contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsBytes(Map.of("session_id", session.toString(), "text", text, "source", "text"))));
  }

  @Test
  void searchItemsUsesDatabaseFtsAndReturnsCurrentItem() throws Exception {
    seed("Подготовить презентацию для демо", ItemType.TASK, "2026-10-30T20:59:00Z");
    seed("Позвонить Ивану", ItemType.REMINDER, "2026-10-29T07:00:00Z");
    stub(payload("search_items", Map.of("query", "презентация")));

    send("Найди задачу про презентацию")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result").value("info"))
        .andExpect(jsonPath("$.intent").value("search_items"))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].title").value("Подготовить презентацию для демо"))
        .andExpect(jsonPath("$.data_changed").value(false));
  }

  @Test
  void byQueryAmbiguityCanChooseCandidateAndCompleteIt() throws Exception {
    seed("Отчёт для Ивана", ItemType.TASK, "2026-10-30T20:59:00Z");
    seed("Отчёт для Петра", ItemType.TASK, "2026-10-31T20:59:00Z");
    stub(payload("complete_item", Map.of("item_ref", "by_query", "query", "отчёт")));
    send("Отметь отчёт выполненным")
        .andExpect(jsonPath("$.result").value("clarification"))
        .andExpect(jsonPath("$.items.length()").value(2));
    assertThat(sessions.findById(session).orElseThrow().getPendingClarification().path("kind").asText())
        .isEqualTo("choose_item");

    wire.resetAll();
    stub(payload("clarification_answer", Map.of("answer", "1")));
    send("Первую")
        .andExpect(jsonPath("$.result").value("done"))
        .andExpect(jsonPath("$.intent").value("complete_item"))
        .andExpect(jsonPath("$.items[0].status").value("done"));
    assertThat(items.findAll().stream().filter(i -> i.getStatus() == ItemStatus.DONE).count()).isEqualTo(1);
  }

  @Test
  void historyIsOldestFirstAndContainsLinkedCurrentItem() throws Exception {
    stub(payload("create_item", Map.of("item_type", "task", "title", "Отправить отчёт", "date_expr", "завтра")));
    send("Поставь задачу отправить отчёт завтра").andExpect(status().isOk());

    mvc.perform(get("/api/history").param("session_id", session.toString()).param("limit", "50"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.messages.length()").value(2))
        .andExpect(jsonPath("$.messages[0].role").value("user"))
        .andExpect(jsonPath("$.messages[1].role").value("assistant"))
        .andExpect(jsonPath("$.messages[1].item.title").value("Отправить отчёт"));
  }

  @Test
  void historyRejectsLimitAbove200() throws Exception {
    mvc.perform(get("/api/history").param("session_id", session.toString()).param("limit", "201"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("REQUEST_INVALID"));
  }
}
