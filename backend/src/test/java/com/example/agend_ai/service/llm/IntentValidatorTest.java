package com.example.agend_ai.service.llm;

import static org.assertj.core.api.Assertions.*;

import com.example.agend_ai.api.error.*;
import com.example.agend_ai.config.LlmProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IntentValidatorTest {
  final ObjectMapper mapper = new ObjectMapper();
  final IntentValidator validator;

  IntentValidatorTest() throws Exception {
    validator = new IntentValidator(mapper);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"intent\":\"unknown\",\"confidence\":1,\"slots\":{},\"sql\":\"delete from items\"}",
        "{\"intent\":\"unknown\",\"confidence\":\"1\",\"slots\":{}}",
        "{\"intent\":\"unknown\",\"confidence\":-1,\"slots\":{}}",
        "{\"intent\":\"unknown\",\"confidence\":1,\"slots\":[]}",
        "{\"intent\":\"create_item\",\"confidence\":1,\"slots\":{\"item_type\":\"event\"}}",
        "{\"intent\":\"list_items\",\"confidence\":1,\"slots\":{\"type_filter\":[\"task\"]}}",
        "{\"intent\":\"create_item\",\"confidence\":1,\"slots\":{\"title\":\"  \"}}",
        "{\"intent\":\"unknown\",\"intent\":\"delete_item\",\"confidence\":1,\"slots\":{}}",
        "{\"intent\":\"unknown\",\"confidence\":1,\"slots\":{}} {}",
        "```json {\"intent\":\"unknown\",\"confidence\":1,\"slots\":{}} ```"
      })
  void rejectsUntrustedStructures(String value) {
    assertThatThrownBy(() -> validator.parse(value)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsOversizedTitleAndRawOutput() throws Exception {
    String raw =
        mapper.writeValueAsString(
            Map.of(
                "intent",
                "create_item",
                "confidence",
                1,
                "slots",
                Map.of("title", "я".repeat(256))));
    assertThatThrownBy(() -> validator.parse(raw)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> validator.parse(" ".repeat(16001)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void acceptsNullableSlotsAndEveryWhitelistedIntent() throws Exception {
    for (Intent intent : Intent.values()) {
      String raw =
          mapper.writeValueAsString(
              Map.of(
                  "intent",
                  intent.name(),
                  "confidence",
                  0.8,
                  "slots",
                  Collections.singletonMap("title", null)));
      assertThat(validator.parse(raw).get("intent").asText()).isEqualTo(intent.name());
    }
  }

  @Test
  void missingCredentialsFailLocallyWithCatalogCode() {
    var props =
        new LlmProperties(
            "yandexgpt", "yandexgpt/latest", "", "", "https://ai.api.cloud.yandex.net", 15, 1000);
    var client = new YandexGptClient(props, validator);
    assertThatThrownBy(() -> client.generateStructuredJson("system", "data"))
        .isInstanceOfSatisfying(
            AppException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.LLM_UNAVAILABLE));
  }
}
