package com.example.agend_ai.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Locale;

public final class EvaluationReportWriter {
  private final ObjectMapper mapper;

  public EvaluationReportWriter(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public WrittenReports write(Path outputDir, NluEvaluationReport report) throws IOException {
    Files.createDirectories(outputDir);
    String stamp = report.generatedAt().replace(':', '-').replace('.', '-');
    Path json = outputDir.resolve("run-" + stamp + ".json");
    Path markdown = outputDir.resolve("run-" + stamp + ".md");
    mapper.writerWithDefaultPrettyPrinter().writeValue(json.toFile(), report);
    Files.writeString(markdown, markdown(report), StandardCharsets.UTF_8);
    Files.copy(json, outputDir.resolve("latest.json"), StandardCopyOption.REPLACE_EXISTING);
    Files.copy(markdown, outputDir.resolve("latest.md"), StandardCopyOption.REPLACE_EXISTING);
    return new WrittenReports(json, markdown);
  }

  public void writeBaseline(Path outputDir, NluEvaluationReport report) throws IOException {
    Files.createDirectories(outputDir);
    // Baseline is intentionally compact: raw provider outputs stay only in ignored run reports.
    NluEvaluationReport compact =
        new NluEvaluationReport(
            report.generatedAt(),
            report.datasetVersion(),
            report.provider(),
            report.model(),
            report.datasetPath(),
            report.metrics(),
            report.byIntent(),
            new NluEvaluationReport.BaselineComparison(false, null, null, null, null, null, false),
            java.util.List.of());
    mapper.writerWithDefaultPrettyPrinter()
        .writeValue(outputDir.resolve("baseline.json").toFile(), compact);
  }

  private String markdown(NluEvaluationReport report) {
    var m = report.metrics();
    StringBuilder out = new StringBuilder();
    out.append("# agendAI NLU evaluation\n\n");
    out.append("Generated: `").append(report.generatedAt()).append("`  \n");
    out.append("Dataset: `").append(report.datasetVersion()).append("`  \n");
    out.append("Provider/model: `").append(report.provider()).append(" / ").append(report.model()).append("`\n\n");
    out.append("## Summary\n\n");
    out.append("| Metric | Value |\n|---|---:|\n");
    metric(out, "Examples", Integer.toString(m.examples()));
    metric(out, "Schema valid", pct(m.schemaValidRate()));
    metric(out, "First-pass schema valid", pct(m.firstPassSchemaValidRate()));
    metric(out, "Intent accuracy", pct(m.intentAccuracy()));
    metric(out, "Slot accuracy", pct(m.slotAccuracy()));
    metric(out, "Critical-slot accuracy", pct(m.criticalSlotAccuracy()));
    metric(out, "Exact example accuracy", pct(m.exactExampleAccuracy()));
    metric(out, "Retry rate", pct(m.retryRate()) + " (" + m.totalRetries() + " retries)");
    metric(out, "Latency p50", m.latencyP50Ms() + " ms");
    metric(out, "Latency p95", m.latencyP95Ms() + " ms");

    out.append("\n## By intent\n\n");
    out.append("| Intent | N | Intent accuracy | Exact |\n|---|---:|---:|---:|\n");
    report.byIntent().forEach((intent, value) ->
        out.append('|').append(intent).append('|')
            .append(value.examples()).append('|')
            .append(pct(value.intentAccuracy())).append('|')
            .append(pct(value.exactExampleAccuracy())).append("|\n"));

    if (report.baseline() != null && report.baseline().available()) {
      var b = report.baseline();
      out.append("\n## Baseline delta\n\n");
      out.append("| Metric | Delta |\n|---|---:|\n");
      metric(out, "Intent accuracy", signedPct(b.intentAccuracyDelta()));
      metric(out, "Slot accuracy", signedPct(b.slotAccuracyDelta()));
      metric(out, "Critical-slot accuracy", signedPct(b.criticalSlotAccuracyDelta()));
      metric(out, "Schema valid", signedPct(b.schemaValidRateDelta()));
      metric(out, "Latency p95", signedLong(b.latencyP95DeltaMs()) + " ms");
      out.append("\nRegression: **").append(b.regression() ? "YES" : "no").append("**\n");
    }

    out.append("\n## Failed examples\n\n");
    boolean any = false;
    for (var r : report.examples()) {
      if (r.exact()) continue;
      any = true;
      out.append("### ").append(r.id()).append("\n\n");
      out.append("- Text: `").append(escapeInline(r.text())).append("`\n");
      out.append("- Intent: expected `").append(r.expectedIntent()).append("`, actual `")
          .append(r.actualIntent() == null ? "<none>" : r.actualIntent()).append("`\n");
      out.append("- Schema valid: ").append(r.schemaValid()).append("; retries: ")
          .append(r.retries()).append("; latency: ").append(r.latencyMs()).append(" ms\n");
      if (r.providerError() != null) {
        out.append("- Provider error: `").append(escapeInline(r.providerError())).append("`\n");
      }
      for (var slot : r.slots()) {
        if (slot.matches()) continue;
        out.append("- Slot `").append(slot.slot()).append("`: expected `")
            .append(escapeInline(String.valueOf(slot.expected()))).append("`, actual `")
            .append(escapeInline(String.valueOf(slot.actual()))).append("` (")
            .append(slot.method()).append(")")
            .append(slot.critical() ? " **critical**" : "")
            .append("\n");
      }
      out.append('\n');
    }
    if (!any) out.append("All selected examples passed.\n");
    return out.toString();
  }

  private static void metric(StringBuilder out, String name, String value) {
    out.append('|').append(name).append('|').append(value).append("|\n");
  }

  private static String pct(double value) {
    return String.format(Locale.ROOT, "%.2f%%", value * 100.0);
  }

  private static String signedPct(Double value) {
    return value == null ? "n/a" : String.format(Locale.ROOT, "%+.2f pp", value * 100.0);
  }

  private static String signedLong(Long value) {
    return value == null ? "n/a" : String.format(Locale.ROOT, "%+d", value);
  }

  private static String escapeInline(String value) {
    return value == null ? "null" : value.replace("`", "'").replace("\n", " ");
  }

  public record WrittenReports(Path json, Path markdown) {}
}
