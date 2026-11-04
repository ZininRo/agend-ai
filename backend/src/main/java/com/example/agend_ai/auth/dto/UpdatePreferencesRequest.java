package com.example.agend_ai.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdatePreferencesRequest(@NotBlank @Size(max = 32) String ttsVoice) {}
