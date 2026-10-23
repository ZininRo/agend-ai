package com.example.agend_ai.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record MessageRequest(
        @NotNull UUID sessionId,
        @NotBlank @Size(max = 1000) String text,
        String timezone,
        String source,
        UUID clientMessageId
) {}