package com.example.agend_ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "tts")
public record TtsProperties(
    @DefaultValue("speechkit") String provider,
    @DefaultValue("") String apiKey,
    @DefaultValue("https://tts.api.cloud.yandex.net") String baseUrl,
    @DefaultValue("12") int timeoutSec,
    @DefaultValue("jane") String voice,
    @DefaultValue("") String emotion,
    @DefaultValue("1.0") double speed,
    @DefaultValue("400") int maxChars) {}
