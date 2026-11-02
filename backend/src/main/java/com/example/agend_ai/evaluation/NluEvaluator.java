package com.example.agend_ai.evaluation;

import com.example.agend_ai.service.nlu.NluContext;
import com.example.agend_ai.service.nlu.NluEngine;
import com.example.agend_ai.service.nlu.NluResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

/** Executes the live model against a frozen dataset and calculates reproducible metrics. */
public final class NluEvaluator {
  private final NluEngine engine;
  private final SlotComparator slots = new SlotComparator();
  private final String provider;
  private final String model;
  private final String datasetPath;

  public NluEvaluator(
      NluEngine engine, String provider, String model, String datasetPath) {
    this.engine = engine;
    this.provider = provider;
    this.model = model;
    this.datasetPath = datasetPath;
  }

  public NluEvaluationReport evaluate(
      EvaluationDataset dataset,
      Set<String> onlyIds,
      int limit,
      NluEvaluationReport baseline) {
    validate(dataset);
    Instant defaultNow = Instant.parse(dataset.fixedContext().now());
    ZoneId defaultZone = ZoneId.of(dataset.fixedContext().timezone());

    List<EvaluationDataset.Example> selected =
        dataset.examples().stream()
            .filter(e -> onlyIds == null || onlyIds.isEmpty() || onlyIds.contains(e.id()))
            .limit(limit <= 0 ? Long.MAX_VALUE : limit)
            .toList();
    if (selected.isEmpty()) throw new IllegalArgumentException("No evaluation examples selected");

    List<NluEvaluationReport.ExampleResult> results = new ArrayList<>();
    for (EvaluationDataset.Example example : selected) {
      Instant now = defaultNow;
      ZoneId zone = defaultZone;
      NluContext context = toContext(example, now, zone);
      long started = System.nanoTime();
      try {
        NluResult result = engine.understand(context);
        JsonNode payload = result.payload();
        String actualIntent = payload == null ? null : payload.path("intent").asText(null);
        JsonNode actualSlots = payload == null ? null : payload.path("slots");
        SlotComparator.Comparison slotComparison = slots.compare(example, actualSlots, now, zone);
        boolean intentCorrect = Objects.equals(example.expectedIntent(), actualIntent);
        boolean exact = result.schemaValid() && intentCorrect && slotComparison.allMatch();
        results.add(
            new NluEvaluationReport.ExampleResult(
                example.id(),
                example.text(),
                example.expectedIntent(),
                actualIntent,
                result.schemaValid(),
                result.firstPassSchemaValid(),
                intentCorrect,
                slotComparison.allMatch(),
                exact,
                payload == null ? 0.0 : payload.path("confidence").asDouble(0.0),
                result.retryCount(),
                result.totalLatencyMs(),
                null,
                slotComparison.slots(),
                result.attempts()));
      } catch (RuntimeException providerFailure) {
        long latencyMs = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
        SlotComparator.Comparison slotComparison = slots.compare(example, null, now, zone);
        results.add(
            new NluEvaluationReport.ExampleResult(
                example.id(),
                example.text(),
                example.expectedIntent(),
                null,
                false,
                false,
                false,
                slotComparison.allMatch(),
                false,
                0.0,
                0,
                latencyMs,
                providerFailure.getClass().getSimpleName() + ": " + safeMessage(providerFailure),
                slotComparison.slots(),
                List.of()));
      }
    }

    NluEvaluationReport.Metrics metrics = metrics(results);
    Map<String, NluEvaluationReport.IntentMetrics> byIntent = byIntent(results);
    NluEvaluationReport.BaselineComparison comparison = compare(metrics, baseline, dataset.version());
    return new NluEvaluationReport(
        Instant.now().toString(),
        dataset.version(),
        provider,
        model,
        datasetPath,
        metrics,
        byIntent,
        comparison,
        results);
  }

  private static NluContext toContext(
      EvaluationDataset.Example example, Instant now, ZoneId zone) {
    EvaluationDataset.Context c = example.context();
    List<NluContext.HistoryEntry> history =
        c == null || c.history() == null
            ? List.of()
            : c.history().stream()
                .map(h -> new NluContext.HistoryEntry(h.role(), h.text()))
                .toList();
    return new NluContext(
        now,
        zone,
        example.text(),
        history,
        c == null ? null : c.lastItem(),
        c == null ? null : c.pending());
  }

  private static void validate(EvaluationDataset dataset) {
    if (dataset == null || dataset.examples() == null || dataset.examples().isEmpty()) {
      throw new IllegalArgumentException("Dataset has no examples");
    }
    if (dataset.fixedContext() == null
        || dataset.fixedContext().now() == null
        || dataset.fixedContext().timezone() == null) {
      throw new IllegalArgumentException("Dataset fixed_context.now/timezone are required");
    }
    Instant.parse(dataset.fixedContext().now());
    ZoneId.of(dataset.fixedContext().timezone());
    Set<String> ids = new HashSet<>();
    for (EvaluationDataset.Example e : dataset.examples()) {
      if (e.id() == null || e.id().isBlank() || !ids.add(e.id())) {
        throw new IllegalArgumentException("Duplicate or blank example id: " + e.id());
      }
      if (e.text() == null || e.text().isBlank() || e.expectedIntent() == null) {
        throw new IllegalArgumentException("Invalid example: " + e.id());
      }
      if (e.context() == null) {
        throw new IllegalArgumentException("Machine-readable context is required: " + e.id());
      }
    }
  }

  private static NluEvaluationReport.Metrics metrics(
      List<NluEvaluationReport.ExampleResult> results) {
    int total = results.size();
    int schemaValid = (int) results.stream().filter(NluEvaluationReport.ExampleResult::schemaValid).count();
    int firstPass = (int) results.stream().filter(NluEvaluationReport.ExampleResult::firstPassSchemaValid).count();
    int intentCorrect = (int) results.stream().filter(NluEvaluationReport.ExampleResult::intentCorrect).count();
    int exact = (int) results.stream().filter(NluEvaluationReport.ExampleResult::exact).count();
    long slotChecks = results.stream().flatMap(r -> r.slots().stream()).count();
    long slotCorrect = results.stream().flatMap(r -> r.slots().stream()).filter(SlotComparator.SlotResult::matches).count();
    long criticalChecks = results.stream().flatMap(r -> r.slots().stream()).filter(SlotComparator.SlotResult::critical).count();
    long criticalCorrect = results.stream().flatMap(r -> r.slots().stream()).filter(s -> s.critical() && s.matches()).count();
    int retries = results.stream().mapToInt(NluEvaluationReport.ExampleResult::retries).sum();
    int retriedExamples = (int) results.stream().filter(r -> r.retries() > 0).count();
    List<Long> latencies = results.stream().map(NluEvaluationReport.ExampleResult::latencyMs).sorted().toList();
    return new NluEvaluationReport.Metrics(
        total,
        schemaValid,
        rate(schemaValid, total),
        firstPass,
        rate(firstPass, total),
        intentCorrect,
        rate(intentCorrect, total),
        slotChecks,
        slotCorrect,
        rate(slotCorrect, slotChecks),
        criticalChecks,
        criticalCorrect,
        rate(criticalCorrect, criticalChecks),
        exact,
        rate(exact, total),
        retries,
        retriedExamples,
        rate(retriedExamples, total),
        percentile(latencies, 0.50),
        percentile(latencies, 0.95));
  }

  private static Map<String, NluEvaluationReport.IntentMetrics> byIntent(
      List<NluEvaluationReport.ExampleResult> results) {
    Map<String, List<NluEvaluationReport.ExampleResult>> groups = new TreeMap<>();
    for (var result : results) {
      groups.computeIfAbsent(result.expectedIntent(), ignored -> new ArrayList<>()).add(result);
    }
    Map<String, NluEvaluationReport.IntentMetrics> metrics = new TreeMap<>();
    for (var entry : groups.entrySet()) {
      List<NluEvaluationReport.ExampleResult> group = entry.getValue();
      int total = group.size();
      int schema = (int) group.stream().filter(NluEvaluationReport.ExampleResult::schemaValid).count();
      int intent = (int) group.stream().filter(NluEvaluationReport.ExampleResult::intentCorrect).count();
      int exact = (int) group.stream().filter(NluEvaluationReport.ExampleResult::exact).count();
      metrics.put(
          entry.getKey(),
          new NluEvaluationReport.IntentMetrics(
              total, schema, intent, exact, rate(intent, total), rate(exact, total)));
    }
    return metrics;
  }

  private static NluEvaluationReport.BaselineComparison compare(
      NluEvaluationReport.Metrics current, NluEvaluationReport baseline, String datasetVersion) {
    if (baseline == null
        || baseline.metrics() == null
        || baseline.metrics().examples() != current.examples()
        || !Objects.equals(baseline.datasetVersion(), datasetVersion)) {
      return new NluEvaluationReport.BaselineComparison(false, null, null, null, null, null, false);
    }
    var b = baseline.metrics();
    double intent = current.intentAccuracy() - b.intentAccuracy();
    double slot = current.slotAccuracy() - b.slotAccuracy();
    double critical = current.criticalSlotAccuracy() - b.criticalSlotAccuracy();
    double schema = current.schemaValidRate() - b.schemaValidRate();
    long latency = current.latencyP95Ms() - b.latencyP95Ms();
    boolean regression = intent < -0.000001 || critical < -0.000001 || schema < -0.000001;
    return new NluEvaluationReport.BaselineComparison(
        true, intent, slot, critical, schema, latency, regression);
  }

  private static double rate(long numerator, long denominator) {
    return denominator == 0 ? 1.0 : (double) numerator / denominator;
  }

  private static long percentile(List<Long> sorted, double p) {
    if (sorted.isEmpty()) return 0L;
    int index = (int) Math.ceil(p * sorted.size()) - 1;
    return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
  }

  private static String safeMessage(Throwable t) {
    String message = t.getMessage();
    if (message == null || message.isBlank()) return "no message";
    return message.length() <= 300 ? message : message.substring(0, 300) + "…";
  }
}
