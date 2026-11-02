package com.example.agend_ai.service.nlu;

/** One provider call made while trying to obtain schema-valid structured NLU output. */
public record NluAttempt(
    int attempt,
    String rawOutput,
    boolean schemaValid,
    String validationError,
    long latencyMs) {}
