package com.example.agend_ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @DefaultValue("Europe/Moscow") String timezone,
        @DefaultValue Context context,
        @DefaultValue Meeting meeting,
        @DefaultValue Pending pending) {

    public record Context(@DefaultValue("10") int maxMessages) {
    }

    public record Meeting(@DefaultValue("60") int defaultDurationMin) {
    }

    public record Pending(@DefaultValue("30") int ttlMin) {
    }
}
