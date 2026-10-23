package com.example.agend_ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "limits")
public record LimitsProperties(
        @DefaultValue("30") int maxAudioSec,
        @DefaultValue("5242880") long maxAudioBytes,
        @DefaultValue("1000") int maxTextChars,
        @DefaultValue Rate rate) {

    public record Rate(
            @DefaultValue("10") int voicePerMin,
            @DefaultValue("30") int messagePerMin) {
    }
}
