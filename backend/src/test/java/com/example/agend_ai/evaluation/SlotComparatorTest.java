package com.example.agend_ai.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SlotComparatorTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final SlotComparator comparator = new SlotComparator();
  private final Instant now = Instant.parse("2026-11-02T09:00:00Z");
  private final ZoneId zone = ZoneId.of("Europe/Moscow");

  @Test
  void temporalSlotsUseProductionDateResolver() {
    var example =
        example(
            Map.of(
                "date_expr", text("пятница"),
                "time_expr", text("15:00")),
            List.of("date_expr", "time_expr"),
            null);
    JsonNode actual = mapper.valueToTree(Map.of("date_expr", "в пятницу", "time_expr", "в три"));

    var result = comparator.compare(example, actual, now, zone);

    assertThat(result.allMatch()).isTrue();
    assertThat(result.slots()).allMatch(SlotComparator.SlotResult::matches);
  }

  @Test
  void pendingTimeAnswerCanMatchEquivalentNaturalTime() {
    JsonNode pending =
        mapper.valueToTree(
            Map.of(
                "kind", "missing_slot",
                "intent", "create_item",
                "awaiting", List.of("time"),
                "created_at", "2026-11-02T08:55:00Z"));
    var example = example(Map.of("answer", text("15:00")), List.of("answer"), pending);
    JsonNode actual = mapper.valueToTree(Map.of("answer", "В три"));

    var result = comparator.compare(example, actual, now, zone);

    assertThat(result.allMatch()).isTrue();
    assertThat(result.slots().get(0).method()).isEqualTo("date_resolver");
  }


  @Test
  void blankOptionalSlotsAreTreatedAsAbsent() {
    var example =
        example(
            Map.of(
                "item_type", text("reminder"),
                "title", text("позвонить Ивану"),
                "date_expr", text("завтра"),
                "time_expr", text("10:00")),
            List.of("item_type", "title", "date_expr", "time_expr"),
            null);
    JsonNode actual =
        mapper.valueToTree(
            Map.of(
                "item_type", "reminder",
                "title", "позвонить Ивану",
                "date_expr", "завтра",
                "time_expr", "10:00",
                "description", "",
                "place", "   ",
                "target_query", ""));

    var result = comparator.compare(example, actual, now, zone);

    assertThat(result.allMatch()).isTrue();
    assertThat(result.total()).isEqualTo(4);
  }

  @Test
  void unexpectedNonNullSlotIsARegression() {
    var example = example(Map.of("period_expr", text("сегодня")), List.of("period_expr"), null);
    JsonNode actual = mapper.valueToTree(Map.of("period_expr", "сегодня", "type_filter", "meeting"));

    var result = comparator.compare(example, actual, now, zone);

    assertThat(result.allMatch()).isFalse();
    assertThat(result.slots()).anyMatch(s -> s.slot().equals("type_filter") && !s.matches());
  }

  private EvaluationDataset.Example example(
      Map<String, JsonNode> expected, List<String> critical, JsonNode pending) {
    return new EvaluationDataset.Example(
        "T",
        "text",
        "create_item",
        expected,
        critical,
        "behavior",
        "style",
        "reply",
        new EvaluationDataset.Context(List.of(), null, pending));
  }

  private JsonNode text(String value) {
    return mapper.getNodeFactory().textNode(value);
  }
}
