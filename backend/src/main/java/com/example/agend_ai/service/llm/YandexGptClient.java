package com.example.agend_ai.service.llm;

import com.example.agend_ai.api.error.*;
import com.example.agend_ai.config.LlmProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.SocketTimeoutException;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

/**
 * Адаптер YandexGPT: POST {base-url}/foundationModels/v1/completion.
 *
 * <p>Структурированный вывод поддерживают не все модели и не все ключевые слова JSON Schema, поэтому
 * клиент пробует по убыванию строгости: {@code jsonSchema} → {@code jsonObject} → обычный запрос.
 * Если провайдер отвечает 400, клиент переходит на следующий режим и запоминает его: дальше запросы
 * идут сразу в рабочем режиме. На безопасность это не влияет: ответ в любом случае проверяет
 * {@link IntentValidator}.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "yandexgpt", matchIfMissing = true)
public class YandexGptClient implements LlmClient {
  private static final int MODE_SCHEMA = 0, MODE_OBJECT = 1, MODE_PLAIN = 2;
  private static final String[] MODE_NAMES = {"jsonSchema", "jsonObject", "обычный текст"};

  private final RestClient client;
  private final LlmProperties props;
  private final IntentValidator validator;
  private volatile int mode = MODE_SCHEMA;

  public YandexGptClient(LlmProperties props, IntentValidator validator) {
    this.props = props;
    this.validator = validator;
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(java.time.Duration.ofSeconds(Math.min(5, props.timeoutSec())));
    factory.setReadTimeout(java.time.Duration.ofSeconds(props.timeoutSec()));
    client =
            RestClient.builder()
                    .baseUrl(props.baseUrl())
                    .requestFactory(factory)
                    .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .build();
    if (props.apiKey().isBlank() || props.folderId().isBlank())
      log.warn(
              "YandexGPT не настроен: задайте LLM_API_KEY и LLM_FOLDER_ID в файле .env."
                      + " Пока их нет, /api/message будет отвечать ошибкой LLM_UNAVAILABLE.");
  }

  @Override
  public String generateStructuredJson(String systemPrompt, String userText) {
    if (props.apiKey().isBlank() || props.folderId().isBlank()) {
      log.error("YandexGPT не настроен: не заданы LLM_API_KEY и/или LLM_FOLDER_ID");
      throw new AppException(ErrorCode.LLM_UNAVAILABLE);
    }
    int current = mode;
    while (true) {
      try {
        return send(systemPrompt, userText, current);
      } catch (HttpClientErrorException.BadRequest e) {
        log.warn(
                "YandexGPT отклонил запрос в режиме «{}»: {}",
                MODE_NAMES[current],
                snippet(e.getResponseBodyAsString()));
        if (current >= MODE_PLAIN) throw new AppException(ErrorCode.LLM_UNAVAILABLE);
        current++;
        mode = Math.max(mode, current);
        log.warn("Переключаюсь на режим «{}»", MODE_NAMES[current]);
      }
    }
  }

  private String send(String systemPrompt, String userText, int currentMode) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("modelUri", "gpt://" + props.folderId() + "/" + props.model());
    body.put(
            "completionOptions",
            Map.of("stream", false, "temperature", 0, "maxTokens", String.valueOf(props.maxTokens())));
    if (currentMode == MODE_SCHEMA) body.put("jsonSchema", Map.of("schema", providerSchema()));
    if (currentMode == MODE_OBJECT) body.put("jsonObject", true);
    body.put(
            "messages",
            List.of(
                    Map.of("role", "system", "text", systemPrompt),
                    Map.of("role", "user", "text", userText)));
    JsonNode response;
    try {
      response =
              client
                      .post()
                      .uri("/foundationModels/v1/completion")
                      .header(HttpHeaders.AUTHORIZATION, "Api-Key " + props.apiKey())
                      .header("x-folder-id", props.folderId())
                      .header("x-data-logging-enabled", "false")
                      .body(body)
                      .retrieve()
                      .body(JsonNode.class);
    } catch (HttpClientErrorException.BadRequest e) {
      throw e; // обработает вызывающий метод: переход на менее строгий режим
    } catch (RestClientResponseException e) {
      int status = e.getStatusCode().value();
      log.warn(
              "YandexGPT вернул HTTP {} — {}. Ответ провайдера: {}",
              status,
              hint(status),
              snippet(e.getResponseBodyAsString()));
      throw new AppException(
              status == 408 || status == 504 ? ErrorCode.LLM_TIMEOUT : ErrorCode.LLM_UNAVAILABLE);
    } catch (RestClientException e) {
      for (Throwable cause = e; cause != null; cause = cause.getCause())
        if (cause instanceof SocketTimeoutException
                || cause instanceof java.net.http.HttpTimeoutException) {
          log.warn("YandexGPT не ответил за {} с", props.timeoutSec());
          throw new AppException(ErrorCode.LLM_TIMEOUT);
        }
      log.warn(
              "YandexGPT недоступен по адресу {}: {}. Проверьте LLM_BASE_URL и подключение к интернету.",
              props.baseUrl(),
              e.getClass().getSimpleName());
      throw new AppException(ErrorCode.LLM_UNAVAILABLE);
    }
    if (response == null) throw new AppException(ErrorCode.LLM_UNAVAILABLE);
    JsonNode alt = response.path("result").path("alternatives").path(0);
    String status = alt.path("status").asText();
    if (!status.equals("ALTERNATIVE_STATUS_FINAL")) {
      log.warn("YandexGPT вернул неполный ответ, статус: {}", status);
      return ""; // truncated/refused: schema retry, never execute
    }
    JsonNode text = alt.path("message").path("text");
    return text.isTextual() ? text.asText() : "";
  }

  /**
   * Схема для провайдера: без {@code null} в типах и перечислениях и без ограничений длины/диапазона.
   * Эти ограничения строго проверяет {@link IntentValidator} уже на нашей стороне.
   */
  private JsonNode providerSchema() {
    JsonNode schema = validator.schema();
    simplify(schema);
    return schema;
  }

  private static void simplify(JsonNode node) {
    if (node instanceof ObjectNode object) {
      for (String key : List.of("minLength", "maxLength", "minimum", "maximum")) object.remove(key);
      JsonNode type = object.get("type");
      if (type != null && type.isArray()) {
        String chosen = "string";
        for (JsonNode t : type)
          if (!t.asText().equals("null")) {
            chosen = t.asText();
            break;
          }
        object.put("type", chosen);
      }
      if (object.get("enum") instanceof ArrayNode values)
        for (int i = values.size() - 1; i >= 0; i--) if (values.get(i).isNull()) values.remove(i);
      object.fields().forEachRemaining(e -> simplify(e.getValue()));
    } else if (node instanceof ArrayNode array) {
      array.forEach(YandexGptClient::simplify);
    }
  }

  private static String hint(int status) {
    return switch (status) {
      case 401 -> "ключ не принят: проверьте LLM_API_KEY (скопируйте целиком, без пробелов)";
      case 403 ->
              "нет прав: у сервисного аккаунта должна быть роль ai.languageModels.user, а LLM_FOLDER_ID"
                      + " должен быть идентификатором того же каталога";
      case 404 -> "адрес или модель не найдены: проверьте LLM_BASE_URL и LLM_MODEL";
      case 429 -> "превышен лимит запросов или исчерпан баланс/квота в Yandex Cloud";
      default -> status >= 500 ? "сбой на стороне провайдера, повторите позже" : "ошибка запроса";
    };
  }

  private static String snippet(String body) {
    if (body == null) return "";
    String flat = body.replaceAll("\\s+", " ").strip();
    return flat.length() <= 300 ? flat : flat.substring(0, 300) + "…";
  }
}
