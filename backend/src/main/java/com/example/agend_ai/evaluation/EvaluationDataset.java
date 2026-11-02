package com.example.agend_ai.evaluation;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/** Versioned, machine-readable NLU evaluation dataset. */
public record EvaluationDataset(
    String version,
    String purpose,
    @JsonProperty("fixed_context") FixedContext fixedContext,
    List<String> notes,
    List<Example> examples) {

  public record FixedContext(String now, String timezone) {}

  public record Example(
      String id,
      String text,
      @JsonProperty("expected_intent") String expectedIntent,
      @JsonProperty("expected_slots") Map<String, JsonNode> expectedSlots,
      @JsonProperty("critical_slots") List<String> criticalSlots,
      @JsonProperty("expected_behavior") String expectedBehavior,
      String style,
      @JsonProperty("expected_reply_style") String expectedReplyStyle,
      Context context) {}

  public record Context(
      List<HistoryEntry> history,
      @JsonProperty("last_item") JsonNode lastItem,
      JsonNode pending) {}

  public record HistoryEntry(String role, String text) {}
}
