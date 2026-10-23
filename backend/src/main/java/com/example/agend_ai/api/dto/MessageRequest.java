package com.example.agend_ai.api.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record MessageRequest(
    @NotNull UUID sessionId,
    @NotNull String text,
    String timezone,
    String source,
    UUID clientMessageId) {}
