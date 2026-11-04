package com.example.agend_ai.api.dto;

import jakarta.validation.constraints.NotBlank;

public record TtsPreviewRequest(@NotBlank String voice) {}
