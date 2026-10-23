package com.example.agend_ai.service.llm;

import com.example.agend_ai.api.error.AppException;
import com.example.agend_ai.api.error.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class YandexGptClient implements LlmClient {

    private final RestClient restClient;
    private final String modelUri;
    private final boolean enabled;

    public YandexGptClient(
            @Value("${llm.provider:}") String provider,
            @Value("${llm.api-key:}") String apiKey,
            @Value("${llm.model:yandexgpt/latest}") String model,
            @Value("${llm.folder-id:default-folder}") String folderId,
            @Value("${llm.base-url:https://ai.api.cloud.yandex.net}") String baseUrl) {

        this.enabled = "yandexgpt".equalsIgnoreCase(provider) || baseUrl.contains("localhost");
        this.modelUri = "gpt://" + folderId + "/" + model;

        // Pilitentayo nga usaren ti HTTP/1.1 babaen ti SimpleClientHttpRequestFactory
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(5000);
        requestFactory.setReadTimeout(15000);

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Api-Key " + (apiKey != null ? apiKey : "test-key"))
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public String generateStructuredJson(String systemPrompt, String userText) {
        if (!enabled) {
            log.warn("YandexGPT is disabled or not configured.");
            throw new AppException(ErrorCode.LLM_UNAVAILABLE);
        }

        Map<String, Object> requestBody = Map.of(
                "modelUri", modelUri,
                "completionOptions", Map.of(
                        "stream", false,
                        "temperature", 0.0,
                        "maxTokens", 1000
                ),
                "messages", List.of(
                        Map.of("role", "system", "text", systemPrompt),
                        Map.of("role", "user", "text", userText)
                )
        );

        try {
            Map<String, Object> response = restClient.post()
                    .uri("/foundationModels/v1/completion")
                    .body(requestBody)
                    .retrieve()
                    .body(Map.class);

            if (response != null && response.containsKey("result")) {
                Map<String, Object> result = (Map<String, Object>) response.get("result");
                List<Map<String, Object>> alternatives = (List<Map<String, Object>>) result.get("alternatives");
                if (alternatives != null && !alternatives.isEmpty()) {
                    Map<String, Object> message = (Map<String, Object>) alternatives.get(0).get("message");
                    return (String) message.get("text");
                }
            }
            throw new AppException(ErrorCode.LLM_UNAVAILABLE);
        } catch (Exception e) {
            log.error("Failed to execute request to YandexGPT", e);
            throw new AppException(ErrorCode.LLM_UNAVAILABLE);
        }
    }
}