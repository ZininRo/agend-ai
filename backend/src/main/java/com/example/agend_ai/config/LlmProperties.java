package com.example.agend_ai.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("llm")
public record LlmProperties(
    @DefaultValue("yandexgpt")
        @Pattern(regexp = "yandexgpt", message = "поддерживается только значение yandexgpt")
        String provider,
    @DefaultValue("yandexgpt/latest") @NotBlank String model,
    @DefaultValue("") String apiKey,
    @DefaultValue("") String folderId,
    @DefaultValue("https://llm.api.cloud.yandex.net")
        @Pattern(regexp = "https?://\\S+", message = "должен начинаться с http:// или https://")
        String baseUrl,
    @DefaultValue("15") @Min(1) int timeoutSec,
    @DefaultValue("1000") @Min(1) int maxTokens) {}
