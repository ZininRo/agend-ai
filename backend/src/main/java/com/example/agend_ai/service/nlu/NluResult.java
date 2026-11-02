package com.example.agend_ai.service.nlu;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Optional;

/** Detailed NLU result used by production code and the P2 evaluation runner. */
public record NluResult(JsonNode payload, List<NluAttempt> attempts, long totalLatencyMs) {
  public NluResult {
    attempts = attempts == null ? List.of() : List.copyOf(attempts);
    payload = payload == null ? null : payload.deepCopy();
  }

  public Optional<JsonNode> payloadOptional() {
    return Optional.ofNullable(payload == null ? null : payload.deepCopy());
  }

  public boolean schemaValid() {
    return payload != null;
  }

  public boolean firstPassSchemaValid() {
    return !attempts.isEmpty() && attempts.get(0).schemaValid();
  }

  public int retryCount() {
    return Math.max(0, attempts.size() - 1);
  }
}
