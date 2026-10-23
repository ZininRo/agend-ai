package com.example.agend_ai.service.llm;

import com.example.agend_ai.api.error.*;
import com.example.agend_ai.config.LlmProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.SocketTimeoutException;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "yandexgpt", matchIfMissing = true)
public class YandexGptClient implements LlmClient {
  private final RestClient client;
  private final LlmProperties props;
  private final IntentValidator validator;

  public YandexGptClient(LlmProperties props, IntentValidator validator) {
    this.props = props;
    this.validator = validator;
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(java.time.Duration.ofSeconds(props.timeoutSec()));
    factory.setReadTimeout(java.time.Duration.ofSeconds(props.timeoutSec()));
    client =
        RestClient.builder()
            .baseUrl(props.baseUrl())
            .requestFactory(factory)
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build();
  }

  @Override
  public String generateStructuredJson(String systemPrompt, String userText) {
    if (props.apiKey().isBlank() || props.folderId().isBlank())
      throw new AppException(ErrorCode.LLM_UNAVAILABLE);
    Map<String, Object> body =
        Map.of(
            "modelUri", "gpt://" + props.folderId() + "/" + props.model(),
            "completionOptions",
                Map.of(
                    "stream",
                    false,
                    "temperature",
                    0,
                    "maxTokens",
                    String.valueOf(props.maxTokens())),
            "jsonSchema", Map.of("schema", validator.schema()),
            "messages",
                List.of(
                    Map.of("role", "system", "text", systemPrompt),
                    Map.of("role", "user", "text", userText)));
    try {
      JsonNode response =
          client
              .post()
              .uri("/foundationModels/v1/completion")
              .header(HttpHeaders.AUTHORIZATION, "Api-Key " + props.apiKey())
              .header("x-folder-id", props.folderId())
              .header("x-data-logging-enabled", "false")
              .body(body)
              .retrieve()
              .body(JsonNode.class);
      if (response == null) throw new AppException(ErrorCode.LLM_UNAVAILABLE);
      JsonNode alt = response.path("result").path("alternatives").path(0);
      String status = alt.path("status").asText();
      if (!status.equals("ALTERNATIVE_STATUS_FINAL"))
        return ""; // truncated/refused: schema retry, never execute
      JsonNode text = alt.path("message").path("text");
      return text.isTextual() ? text.asText() : "";
    } catch (RestClientException e) {
      for (Throwable cause = e; cause != null; cause = cause.getCause())
        if (cause instanceof SocketTimeoutException
            || cause instanceof java.net.http.HttpTimeoutException)
          throw new AppException(ErrorCode.LLM_TIMEOUT);
      throw new AppException(ErrorCode.LLM_UNAVAILABLE);
    }
  }
}
