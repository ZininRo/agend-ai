package com.example.agend_ai.evaluation;

import com.example.agend_ai.service.nlu.NluAttempt;
import java.util.List;
import java.util.Map;

public record NluEvaluationReport(
    String generatedAt,
    String datasetVersion,
    String provider,
    String model,
    String datasetPath,
    Metrics metrics,
    Map<String, IntentMetrics> byIntent,
    BaselineComparison baseline,
    List<ExampleResult> examples) {

  public record Metrics(
      int examples,
      int schemaValid,
      double schemaValidRate,
      int firstPassSchemaValid,
      double firstPassSchemaValidRate,
      int intentCorrect,
      double intentAccuracy,
      long slotChecks,
      long slotCorrect,
      double slotAccuracy,
      long criticalSlotChecks,
      long criticalSlotCorrect,
      double criticalSlotAccuracy,
      int exactExamples,
      double exactExampleAccuracy,
      int totalRetries,
      int examplesRetried,
      double retryRate,
      long latencyP50Ms,
      long latencyP95Ms) {}

  public record IntentMetrics(
      int examples,
      int schemaValid,
      int intentCorrect,
      int exactExamples,
      double intentAccuracy,
      double exactExampleAccuracy) {}

  public record BaselineComparison(
      boolean available,
      Double intentAccuracyDelta,
      Double slotAccuracyDelta,
      Double criticalSlotAccuracyDelta,
      Double schemaValidRateDelta,
      Long latencyP95DeltaMs,
      boolean regression) {}

  public record ExampleResult(
      String id,
      String text,
      String expectedIntent,
      String actualIntent,
      boolean schemaValid,
      boolean firstPassSchemaValid,
      boolean intentCorrect,
      boolean slotsCorrect,
      boolean exact,
      double confidence,
      int retries,
      long latencyMs,
      String providerError,
      List<SlotComparator.SlotResult> slots,
      List<NluAttempt> attempts) {}
}
