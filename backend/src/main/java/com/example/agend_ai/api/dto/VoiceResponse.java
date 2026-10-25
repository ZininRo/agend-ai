package com.example.agend_ai.api.dto;

public record VoiceResponse(
        boolean recognized, String text, double confidence, String assistantMessage) {}
