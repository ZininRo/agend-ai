package com.example.agend_ai.service.nlu;

import com.example.agend_ai.service.llm.IntentValidator;
import com.example.agend_ai.service.llm.LlmClient;
import com.example.agend_ai.service.llm.ModelOutputCleaner;
import com.example.agend_ai.service.llm.PromptBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Pure NLU orchestration: prompt -> provider -> cleanup -> strict schema validation. */
@Component
@Slf4j
@RequiredArgsConstructor
public class NluEngine {
  private final LlmClient llmClient;
  private final IntentValidator validator;
  private final PromptBuilder prompts;
  private final ObjectMapper mapper;

  public NluResult understand(NluContext context) {
    long started = System.nanoTime();
    String data = prompts.data(context);
    var attempts = new ArrayList<NluAttempt>(2);

    for (int attempt = 0; attempt < 2; attempt++) {
      long callStarted = System.nanoTime();
      String generated =
          llmClient.generateStructuredJson(
              prompts.system()
                  + (attempt == 0
                      ? ""
                      : "\nПредыдущий ответ нарушил схему. Верни корректный JSON."),
              data);
      long latencyMs = elapsedMs(callStarted);
      try {
        JsonNode payload = validator.parse(ModelOutputCleaner.clean(generated, mapper));
        attempts.add(new NluAttempt(attempt + 1, generated, true, null, latencyMs));
        return new NluResult(payload, attempts, elapsedMs(started));
      } catch (IllegalArgumentException e) {
        attempts.add(new NluAttempt(attempt + 1, generated, false, e.getMessage(), latencyMs));
        log.warn(
            "NLU: ответ модели отклонён (попытка {} из 2): {}; длина ответа {} симв.",
            attempt + 1,
            e.getMessage(),
            generated == null ? 0 : generated.length());
        if (log.isDebugEnabled()) {
          log.debug(
              "NLU: ответ модели: {}",
              generated == null
                  ? "null"
                  : generated.length() <= 1000
                      ? generated
                      : generated.substring(0, 1000) + "…");
        }
      }
    }
    return new NluResult(null, attempts, elapsedMs(started));
  }

  private static long elapsedMs(long startedNanos) {
    return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
  }
}
