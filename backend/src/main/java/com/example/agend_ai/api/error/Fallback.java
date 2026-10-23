package com.example.agend_ai.api.error;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

public enum Fallback {
    RETRY, TEXT_INPUT, REPHRASE, WAIT, RELOAD;

    @JsonValue
    public String json() {
        return name().toLowerCase(Locale.ROOT);
    }
}
