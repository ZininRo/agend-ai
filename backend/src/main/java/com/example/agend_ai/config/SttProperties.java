package com.example.agend_ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "stt")
public record SttProperties(
        @DefaultValue("speechkit") String provider,
        @DefaultValue("") String apiKey,
        @DefaultValue("https://stt.api.cloud.yandex.net") String baseUrl,
        @DefaultValue("10") int timeoutSec,
        @DefaultValue("0.5") double minConfidence) {}