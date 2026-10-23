package com.example.agend_ai.service.llm;

public interface LlmClient {
    String generateStructuredJson(String systemPrompt, String userText);
}