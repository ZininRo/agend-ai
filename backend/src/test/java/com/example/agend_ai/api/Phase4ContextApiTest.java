package com.example.agend_ai.api;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.agend_ai.entity.ChatSession;
import com.example.agend_ai.repository.ChatSessionRepository;
import com.example.agend_ai.repository.ItemRepository;
import com.example.agend_ai.repository.MessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(Phase4ContextApiTest.FixedTime.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class Phase4ContextApiTest extends DatabaseTestSupport {
  static final WireMockServer wire = new WireMockServer(0);
  static { wire.start(); }

  @TestConfiguration
  static class FixedTime {
    @Bean @Primary Clock fixedClock() {
      return Clock.fixed(Instant.parse("2026-10-23T11:02:47Z"), ZoneOffset.UTC);
    }
  }

  @DynamicPropertySource
  static void llm(DynamicPropertyRegistry r) {
    r.add("llm.base-url", wire::baseUrl);
    r.add("llm.api-key", () -> "test-key");
    r.add("llm.folder-id", () -> "test-folder");
    r.add("llm.timeout-sec", () -> 1);
  }

  @AfterAll static void stop() { wire.stop(); }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired ItemRepository items;
  @Autowired MessageRepository messages;
  @Autowired ChatSessionRepository sessions;
  UUID session;

  @BeforeEach
  void clean() {
    messages.deleteAll();
    sessions.deleteAll();
    items.deleteAll();
    wire.resetAll();
    session = UUID.randomUUID();
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
        .content(mapper.writeValueAsBytes(Map.of(
            "session_id", session.toString(), "text", text, "source", "text"))));
  }

  @Test
  void s4ConflictCreatesPendingAndDoesNotPersistDraft() throws Exception {
    stub(payload("create_item", Map.of(
        "item_type", "meeting", "title", "Встреча с командой", "date_expr", "завтра", "time_expr", "15:00")));
    send("Назначь встречу с командой завтра в 15:00").andExpect(jsonPath("$.result").value("done"));

    wire.resetAll();
    stub(payload("create_item", Map.of(
        "item_type", "meeting", "title", "Встреча с дизайнером", "date_expr", "завтра", "time_expr", "15:00")));
    send("Поставь встречу с дизайнером завтра в 15:00")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result").value("clarification"))
        .andExpect(jsonPath("$.quick_replies[0]").value("Выбрать другое время"))
        .andExpect(jsonPath("$.quick_replies[1]").value("Поставить в это же"));

    assertThat(items.count()).isEqualTo(1);
    ChatSession saved = sessions.findById(session).orElseThrow();
    assertThat(saved.getPendingClarification().path("kind").asText()).isEqualTo("conflict");
  }

  @Test
  void conflictCanBeExplicitlyAccepted() throws Exception {
    stub(payload("create_item", Map.of(
        "item_type", "meeting", "title", "Встреча с командой", "date_expr", "завтра", "time_expr", "15:00")));
    send("Назначь встречу с командой завтра в 15:00");

    wire.resetAll();
    stub(payload("create_item", Map.of(
        "item_type", "meeting", "title", "Встреча с дизайнером", "date_expr", "завтра", "time_expr", "15:00")));
    send("Поставь встречу с дизайнером завтра в 15:00").andExpect(jsonPath("$.result").value("clarification"));

    wire.resetAll();
    stub(payload("clarification_answer", Map.of("answer", "keep_conflict")));
    send("Всё равно ставь")
        .andExpect(jsonPath("$.result").value("done"))
        .andExpect(jsonPath("$.items[0].title").value("Встреча с дизайнером"));
    assertThat(items.count()).isEqualTo(2);
    assertThat(sessions.findById(session).orElseThrow().getPendingClarification()).isNull();
  }

  @Test
  void s5UpdateLastItemKeepsDateAndChangesTime() throws Exception {
    stub(payload("create_item", Map.of(
        "item_type", "meeting", "title", "Встреча с командой", "date_expr", "завтра", "time_expr", "15:00")));
    send("Назначь встречу с командой завтра в 15:00").andExpect(jsonPath("$.result").value("done"));

    wire.resetAll();
    stub(payload("update_item", Map.of("item_ref", "last", "new_time_expr", "9")));
    send("Измени время той встречи на 9")
        .andExpect(jsonPath("$.result").value("done"))
        .andExpect(jsonPath("$.intent").value("update_item"))
        .andExpect(jsonPath("$.items[0].start_at").value("2026-10-24T09:00:00+03:00"))
        .andExpect(jsonPath("$.data_changed").value(true));
  }

  @Test
  void completeLastItemChangesStatus() throws Exception {
    stub(payload("create_item", Map.of(
        "item_type", "task", "title", "Отправить отчёт", "deadline_expr", "завтра")));
    send("Поставь задачу отправить отчёт завтра").andExpect(jsonPath("$.result").value("done"));

    wire.resetAll();
    stub(payload("complete_item", Map.of("item_ref", "last")));
    send("Отметь её выполненной")
        .andExpect(jsonPath("$.result").value("done"))
        .andExpect(jsonPath("$.intent").value("complete_item"))
        .andExpect(jsonPath("$.items[0].status").value("done"));
  }

  @Test
  void missingTimeContinuesFromPendingClarification() throws Exception {
    stub(payload("create_item", Map.of(
        "item_type", "meeting", "title", "Встреча с дизайнером", "date_expr", "завтра")));
    send("Поставь встречу с дизайнером завтра")
        .andExpect(jsonPath("$.result").value("clarification"));

    wire.resetAll();
    stub(payload("clarification_answer", Map.of("answer", "15:00")));
    send("В 15:00")
        .andExpect(jsonPath("$.result").value("done"))
        .andExpect(jsonPath("$.items[0].start_at").value("2026-10-24T15:00:00+03:00"));
  }

  @Test
  void updateConflictCanChooseAnotherTimeAndResumeUpdate() throws Exception {
    // Сначала создаём встречу-блокер в 15:00.
    stub(payload("create_item", Map.of(
        "item_type", "meeting", "title", "Встреча с дизайнером", "date_expr", "завтра", "time_expr", "15:00")));
    send("Назначь встречу с дизайнером завтра в 15:00")
        .andExpect(jsonPath("$.result").value("done"));

    // Затем создаём целевую встречу в 10:00. Она становится last_item.
    wire.resetAll();
    stub(payload("create_item", Map.of(
        "item_type", "meeting", "title", "Встреча с командой", "date_expr", "завтра", "time_expr", "10:00")));
    send("Назначь встречу с командой завтра в 10:00")
        .andExpect(jsonPath("$.result").value("done"));

    UUID targetId = sessions.findById(session).orElseThrow().getLastItem().getId();

    // Перенос в 15:00 конфликтует с первой встречей и не должен менять запись.
    wire.resetAll();
    stub(payload("update_item", Map.of("item_ref", "last", "new_time_expr", "15:00")));
    send("Перенеси ту встречу на 15:00")
        .andExpect(jsonPath("$.result").value("clarification"))
        .andExpect(jsonPath("$.intent").value("update_item"))
        .andExpect(jsonPath("$.quick_replies[0]").value("Выбрать другое время"));
    assertThat(items.findById(targetId).orElseThrow().getStartAt())
        .isEqualTo(Instant.parse("2026-10-24T07:00:00Z"));

    // Пользователь выбирает другое время.
    wire.resetAll();
    stub(payload("clarification_answer", Map.of("answer", "other_time")));
    send("Выбрать другое время")
        .andExpect(jsonPath("$.result").value("clarification"));

    // Новое время должно попасть именно в new_time_expr и продолжить update_item.
    wire.resetAll();
    stub(payload("clarification_answer", Map.of("answer", "17:00")));
    send("В 17:00")
        .andExpect(jsonPath("$.result").value("done"))
        .andExpect(jsonPath("$.intent").value("update_item"))
        .andExpect(jsonPath("$.items[0].id").value(targetId.toString()))
        .andExpect(jsonPath("$.items[0].start_at").value("2026-10-24T17:00:00+03:00"))
        .andExpect(jsonPath("$.data_changed").value(true));

    assertThat(items.count()).isEqualTo(2);
    assertThat(sessions.findById(session).orElseThrow().getPendingClarification()).isNull();
  }

}
