package com.example.agend_ai.evaluation;

import com.example.agend_ai.config.LlmProperties;
import com.example.agend_ai.service.llm.IntentValidator;
import com.example.agend_ai.service.llm.PromptBuilder;
import com.example.agend_ai.service.llm.YandexGptClient;
import com.example.agend_ai.service.nlu.NluEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * P2 live evaluation entry point. It intentionally does not start Spring or connect to PostgreSQL.
 * Production prompt, provider adapter, schema validator and NLU engine are reused directly.
 */
public final class NluEvaluationCli {
  private NluEvaluationCli() {}

  public static void main(String[] args) throws Exception {
    Options options = Options.parse(args);
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    EvaluationDataset dataset = mapper.readValue(options.dataset().toFile(), EvaluationDataset.class);
    Map<String, String> env = DotEnv.load(options.envFile());

    LlmProperties llm =
        new LlmProperties(
            DotEnv.get(env, "LLM_PROVIDER", "yandexgpt"),
            DotEnv.get(env, "LLM_MODEL", "yandexgpt/latest"),
            required(env, "LLM_API_KEY"),
            required(env, "LLM_FOLDER_ID"),
            DotEnv.get(env, "LLM_BASE_URL", "https://llm.api.cloud.yandex.net"),
            integer(env, "LLM_TIMEOUT_SEC", 15),
            integer(env, "LLM_MAX_TOKENS", 1000));

    IntentValidator validator = new IntentValidator(mapper);
    PromptBuilder prompts = new PromptBuilder(mapper);
    NluEngine engine = new NluEngine(new YandexGptClient(llm, validator), validator, prompts, mapper);
    EvaluationReportWriter writer = new EvaluationReportWriter(mapper);

    NluEvaluationReport baseline = null;
    Path baselinePath = options.outputDir().resolve("baseline.json");
    if (Files.isRegularFile(baselinePath)) {
      baseline = mapper.readValue(baselinePath.toFile(), NluEvaluationReport.class);
    }

    NluEvaluator evaluator =
        new NluEvaluator(engine, llm.provider(), llm.model(), options.dataset().toAbsolutePath().normalize().toString());
    NluEvaluationReport report =
        evaluator.evaluate(dataset, options.ids(), options.limit(), baseline);
    EvaluationReportWriter.WrittenReports written = writer.write(options.outputDir(), report);
    if (options.writeBaseline()) writer.writeBaseline(options.outputDir(), report);

    printSummary(report, written, options.writeBaseline());
    enforce(options, report);
  }

  private static void printSummary(
      NluEvaluationReport report,
      EvaluationReportWriter.WrittenReports written,
      boolean baselineWritten) {
    var m = report.metrics();
    System.out.printf("%nNLU evaluation: %d examples%n", m.examples());
    System.out.printf("  schema valid:        %.2f%%%n", m.schemaValidRate() * 100);
    System.out.printf("  first-pass schema:   %.2f%%%n", m.firstPassSchemaValidRate() * 100);
    System.out.printf("  intent accuracy:     %.2f%%%n", m.intentAccuracy() * 100);
    System.out.printf("  slot accuracy:       %.2f%%%n", m.slotAccuracy() * 100);
    System.out.printf("  critical slots:      %.2f%%%n", m.criticalSlotAccuracy() * 100);
    System.out.printf("  exact examples:      %.2f%%%n", m.exactExampleAccuracy() * 100);
    System.out.printf("  retries:             %d (%.2f%% examples)%n", m.totalRetries(), m.retryRate() * 100);
    System.out.printf("  latency p50 / p95:   %d / %d ms%n", m.latencyP50Ms(), m.latencyP95Ms());
    if (report.baseline() != null && report.baseline().available()) {
      System.out.printf("  baseline regression: %s%n", report.baseline().regression() ? "YES" : "no");
    }
    System.out.println("  JSON report:         " + written.json().toAbsolutePath().normalize());
    System.out.println("  Markdown report:     " + written.markdown().toAbsolutePath().normalize());
    if (baselineWritten) System.out.println("  Baseline updated:    baseline.json");
  }

  private static void enforce(Options options, NluEvaluationReport report) {
    var m = report.metrics();
    if (options.failOnRegression()
        && report.baseline() != null
        && report.baseline().available()
        && report.baseline().regression()) {
      throw new IllegalStateException("NLU regression against baseline");
    }
    if (options.minIntent() != null && m.intentAccuracy() < options.minIntent()) {
      throw new IllegalStateException("Intent accuracy is below --min-intent");
    }
    if (options.minCritical() != null && m.criticalSlotAccuracy() < options.minCritical()) {
      throw new IllegalStateException("Critical-slot accuracy is below --min-critical");
    }
    if (options.minSchema() != null && m.schemaValidRate() < options.minSchema()) {
      throw new IllegalStateException("Schema-valid rate is below --min-schema");
    }
  }

  private static String required(Map<String, String> env, String key) {
    String value = DotEnv.get(env, key, "");
    if (value.isBlank()) {
      throw new IllegalArgumentException(key + " is required. Put it in .env or process environment.");
    }
    return value;
  }

  private static int integer(Map<String, String> env, String key, int fallback) {
    return Integer.parseInt(DotEnv.get(env, key, Integer.toString(fallback)));
  }

  record Options(
      Path dataset,
      Path outputDir,
      Path envFile,
      Set<String> ids,
      int limit,
      boolean writeBaseline,
      boolean failOnRegression,
      Double minIntent,
      Double minCritical,
      Double minSchema) {

    static Options parse(String[] args) {
      Path dataset = firstExisting(Path.of("../nlu-evaluation/dataset.json"), Path.of("nlu-evaluation/dataset.json"));
      Path env = firstExisting(Path.of("../.env"), Path.of(".env"));
      Path out = dataset.getParent().resolve("reports");
      Set<String> ids = new LinkedHashSet<>();
      int limit = 0;
      boolean writeBaseline = false;
      boolean failOnRegression = false;
      Double minIntent = null, minCritical = null, minSchema = null;

      for (String arg : args) {
        if (arg.startsWith("--dataset=")) dataset = Path.of(value(arg));
        else if (arg.startsWith("--out=")) out = Path.of(value(arg));
        else if (arg.startsWith("--env=")) env = Path.of(value(arg));
        else if (arg.startsWith("--ids=")) {
          Arrays.stream(value(arg).split(","))
              .map(String::strip)
              .filter(s -> !s.isBlank())
              .forEach(ids::add);
        } else if (arg.startsWith("--limit=")) limit = Integer.parseInt(value(arg));
        else if (arg.equals("--write-baseline")) writeBaseline = true;
        else if (arg.equals("--fail-on-regression")) failOnRegression = true;
        else if (arg.startsWith("--min-intent=")) minIntent = fraction(value(arg));
        else if (arg.startsWith("--min-critical=")) minCritical = fraction(value(arg));
        else if (arg.startsWith("--min-schema=")) minSchema = fraction(value(arg));
        else if (arg.equals("--help") || arg.equals("-h")) {
          usage();
          System.exit(0);
        } else {
          throw new IllegalArgumentException("Unknown argument: " + arg);
        }
      }
      if (!Files.isRegularFile(dataset)) throw new IllegalArgumentException("Dataset not found: " + dataset);
      if (limit < 0) throw new IllegalArgumentException("--limit must be >= 0");
      return new Options(dataset, out, env, Set.copyOf(ids), limit, writeBaseline, failOnRegression,
          minIntent, minCritical, minSchema);
    }

    private static String value(String arg) {
      return arg.substring(arg.indexOf('=') + 1);
    }

    private static double fraction(String value) {
      double parsed = Double.parseDouble(value);
      if (parsed < 0 || parsed > 1) throw new IllegalArgumentException("Threshold must be between 0 and 1");
      return parsed;
    }

    private static Path firstExisting(Path first, Path second) {
      return Files.exists(first) ? first : second;
    }

    private static void usage() {
      System.out.println("""
Usage: NluEvaluationCli [options]
  --dataset=PATH          dataset.json path
  --out=PATH              reports directory
  --env=PATH              .env with LLM_API_KEY / LLM_FOLDER_ID
  --ids=NLU-001,NLU-002   run only selected examples
  --limit=N               run first N selected examples
  --write-baseline        save this run as reports/baseline.json
  --fail-on-regression    non-zero exit when intent/critical/schema regress vs baseline
  --min-intent=0.90       optional minimum intent accuracy
  --min-critical=0.90     optional minimum critical-slot accuracy
  --min-schema=0.99       optional minimum schema-valid rate
""");
    }
  }
}
