package com.example.agend_ai.service.stt;

public record SttResult(String text, double confidence) {
    public SttResult {
        text = text == null ? "" : text.strip();
        confidence = Math.max(0.0, Math.min(1.0, confidence));
    }
}