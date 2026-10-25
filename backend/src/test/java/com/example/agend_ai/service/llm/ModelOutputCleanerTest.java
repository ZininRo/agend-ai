package com.example.agend_ai.service.llm;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ModelOutputCleanerTest {
  private final ObjectMapper mapper = new ObjectMapper();

  private String clean(String raw) {
    return ModelOutputCleaner.clean(raw, mapper);
  }

  private JsonNode parsed(String raw) throws Exception {
    return new IntentValidator(mapper).parse(clean(raw));
  }

  @Test
  void extractsJsonFromMarkdownFenceAndChatter() throws Exception {
    JsonNode n =
        parsed(
            "Вот ответ:\n```json\n{\"intent\":\"list_items\",\"confidence\":0.9,\"slots\":{\"period_expr\":\"сегодня\"}}\n```");
    assertThat(n.get("intent").asText()).isEqualTo("list_items");
    assertThat(n.at("/slots/period_expr").asText()).isEqualTo("сегодня");
  }

  @Test
  void dropsEmptyNullAndPlaceholderSlots() throws Exception {
    JsonNode n =
        parsed(
            "{\"intent\":\"create_item\",\"confidence\":0.95,\"slots\":{\"item_type\":\"meeting\","
                + "\"title\":\"Встреча с командой\",\"date_expr\":\"завтра\",\"time_expr\":\"15:00\","
                + "\"deadline_expr\":\"\",\"place\":\"null\",\"query\":\"  \",\"type_filter\":null,\"answer\":\"None\"}}");
    assertThat(n.at("/slots").fieldNames()).toIterable()
        .containsExactlyInAnyOrder("item_type", "title", "date_expr", "time_expr");
  }

  @Test
  void numbersInSlotsAreNotSilentlyConvertedToStrings() {
    assertThatThrownBy(
            () ->
                parsed(
                    "{\"intent\":\"create_item\",\"confidence\":1,\"slots\":{\"title\":42}}"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                parsed(
                    "{\"intent\":\"create_item\",\"confidence\":1,\"slots\":{\"time_expr\":15}}"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void trimsSpacesAroundSlotValues() throws Exception {
    JsonNode n =
        parsed("{\"intent\":\"create_item\",\"confidence\":0.9,\"slots\":{\"title\":\"  Позвонить Ивану \"}}");
    assertThat(n.at("/slots/title").asText()).isEqualTo("Позвонить Ивану");
  }

  @Test
  void normalizesIntentCaseAndPercentConfidence() throws Exception {
    JsonNode n = parsed("{\"intent\":\" List_Items \",\"confidence\":95,\"slots\":{}}");
    assertThat(n.get("intent").asText()).isEqualTo("list_items");
    assertThat(n.get("confidence").asDouble()).isEqualTo(0.95);
  }

  @Test
  void stillRejectsWhatIsReallyWrong() {
    ObjectMapper m = new ObjectMapper();
    assertThatThrownBy(() -> new IntentValidator(m).parse(clean("{\"intent\":\"delete_all\",\"confidence\":1,\"slots\":{}}")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new IntentValidator(m).parse(clean("{\"intent\":\"unknown\",\"confidence\":1,\"slots\":{},\"extra\":1}")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new IntentValidator(m).parse(clean("{\"intent\":\"create_item\",\"confidence\":1,\"slots\":{\"item_type\":\"event\"}}")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new IntentValidator(m).parse(clean("{\"intent\":\"unknown\",\"confidence\":250,\"slots\":{}}")))
        .isInstanceOf(IllegalArgumentException.class);
    // confidence=2 — не процент и не доля: отвергается, а не превращается в 0.02
    assertThatThrownBy(() -> new IntentValidator(m).parse(clean("{\"intent\":\"create_item\",\"confidence\":2,\"slots\":{}}")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new IntentValidator(m).parse(clean("{\"intent\":\"unknown\",\"slots\":{}}")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void nonJsonIsReturnedUntouchedSoValidatorFails() {
    assertThat(clean("Извините, не могу")).isEqualTo("Извините, не могу");
    assertThat(clean("")).isEqualTo("");
    assertThat(clean(null)).isNull();
    assertThatThrownBy(() -> new IntentValidator(mapper).parse(clean("Извините, не могу")))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
