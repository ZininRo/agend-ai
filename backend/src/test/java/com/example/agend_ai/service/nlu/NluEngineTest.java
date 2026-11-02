package com.example.agend_ai.service.nlu;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agend_ai.service.llm.IntentValidator;
import com.example.agend_ai.service.llm.LlmClient;
import com.example.agend_ai.service.llm.PromptBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.List;
import org.junit.jupiter.api.Test;

class NluEngineTest {
  @Test
  void recordsInvalidFirstAttemptAndRetriesExactlyOnce() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    ArrayDeque<String> outputs = new ArrayDeque<>();
    outputs.add("not-json");
    outputs.add("{\"intent\":\"list_items\",\"confidence\":0.9,\"slots\":{\"period_expr\":\"завтра\"}}");
    LlmClient llm = (system, user) -> outputs.removeFirst();
    IntentValidator validator = new IntentValidator(mapper);
    NluEngine engine = new NluEngine(llm, validator, new PromptBuilder(mapper), mapper);

    NluResult result =
        engine.understand(
            new NluContext(
                Instant.parse("2026-11-02T09:00:00Z"),
                ZoneId.of("Europe/Moscow"),
                "Что у меня завтра?",
                List.of(),
                null,
                null));

    assertThat(result.schemaValid()).isTrue();
    assertThat(result.firstPassSchemaValid()).isFalse();
    assertThat(result.retryCount()).isEqualTo(1);
    assertThat(result.attempts()).hasSize(2);
    assertThat(result.attempts().get(0).rawOutput()).isEqualTo("not-json");
    assertThat(result.attempts().get(0).validationError()).isNotBlank();
    assertThat(result.payload().path("intent").asText()).isEqualTo("list_items");
  }

  @Test
  void stopsAfterFirstValidResponse() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    LlmClient llm =
        (system, user) ->
            "{\"intent\":\"unknown\",\"confidence\":0.8,\"slots\":{}}";
    IntentValidator validator = new IntentValidator(mapper);
    NluEngine engine = new NluEngine(llm, validator, new PromptBuilder(mapper), mapper);

    NluResult result =
        engine.understand(
            new NluContext(
                Instant.parse("2026-11-02T09:00:00Z"),
                ZoneId.of("Europe/Moscow"),
                "Расскажи анекдот",
                List.of(),
                null,
                null));

    assertThat(result.firstPassSchemaValid()).isTrue();
    assertThat(result.retryCount()).isZero();
    assertThat(result.attempts()).hasSize(1);
  }
}
