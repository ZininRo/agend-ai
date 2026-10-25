package com.example.agend_ai.service.llm;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.example.agend_ai.api.error.*;
import com.example.agend_ai.config.LlmProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;

class YandexGptClientTest {
  private static final String PATH = "/foundationModels/v1/completion";
  private static final String OK =
      "{\"result\":{\"alternatives\":[{\"message\":{\"role\":\"assistant\",\"text\":\"{\\\"intent\\\":\\\"unknown\\\"}\"},"
          + "\"status\":\"ALTERNATIVE_STATUS_FINAL\"}]}}";

  private final ObjectMapper mapper = new ObjectMapper();
  private WireMockServer wire;

  @BeforeEach
  void start() {
    wire = new WireMockServer(0);
    wire.start();
  }

  @AfterEach
  void stop() {
    wire.stop();
  }

  private YandexGptClient client(String key, String folder, int timeoutSec) throws Exception {
    var props =
        new LlmProperties("yandexgpt", "yandexgpt/latest", key, folder, wire.baseUrl(), timeoutSec, 500);
    return new YandexGptClient(props, new IntentValidator(mapper));
  }

  @Test
  void firstRequestUsesSimplifiedJsonSchema() throws Exception {
    wire.stubFor(post(urlEqualTo(PATH)).willReturn(okJson(OK)));
    assertThat(client("k", "f", 5).generateStructuredJson("sys", "data")).isEqualTo("{\"intent\":\"unknown\"}");

    wire.verify(
        postRequestedFor(urlEqualTo(PATH))
            .withHeader("Authorization", equalTo("Api-Key k"))
            .withHeader("x-folder-id", equalTo("f"))
            .withRequestBody(matchingJsonPath("$.modelUri", equalTo("gpt://f/yandexgpt/latest"))));
    JsonNode body = mapper.readTree(wire.getAllServeEvents().get(0).getRequest().getBodyAsString());
    JsonNode itemType = body.at("/jsonSchema/schema/properties/slots/properties/item_type");
    assertThat(itemType.get("type").asText()).isEqualTo("string"); // не ["string","null"]
    assertThat(itemType.get("enum")).hasSize(3); // без null
    assertThat(itemType.has("maxLength")).isFalse();
    assertThat(body.at("/jsonSchema/schema/properties/confidence").has("minimum")).isFalse();
  }

  @Test
  void badRequestFallsBackToJsonObjectAndRemembersIt() throws Exception {
    wire.stubFor(
        post(urlEqualTo(PATH))
            .atPriority(1)
            .withRequestBody(matchingJsonPath("$.jsonSchema"))
            .willReturn(aResponse().withStatus(400).withBody("{\"error\":\"schema is not supported\"}")));
    wire.stubFor(post(urlEqualTo(PATH)).atPriority(5).willReturn(okJson(OK)));
    var client = client("k", "f", 5);

    assertThat(client.generateStructuredJson("sys", "data")).isEqualTo("{\"intent\":\"unknown\"}");
    wire.verify(2, postRequestedFor(urlEqualTo(PATH)));
    wire.verify(1, postRequestedFor(urlEqualTo(PATH)).withRequestBody(matchingJsonPath("$.jsonObject", equalTo("true"))));

    client.generateStructuredJson("sys", "data"); // режим запомнен: сразу jsonObject
    wire.verify(3, postRequestedFor(urlEqualTo(PATH)));
  }

  @Test
  void badRequestEverywhereEndsAsLlmUnavailable() throws Exception {
    wire.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(400).withBody("{}")));
    assertThatThrownBy(() -> client("k", "f", 5).generateStructuredJson("sys", "data"))
        .isInstanceOfSatisfying(
            AppException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.LLM_UNAVAILABLE));
    wire.verify(3, postRequestedFor(urlEqualTo(PATH))); // jsonSchema → jsonObject → обычный
  }

  @Test
  void gatewayTimeoutStatusMapsToTimeout() throws Exception {
    wire.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(504)));
    assertThatThrownBy(() -> client("k", "f", 5).generateStructuredJson("sys", "data"))
        .isInstanceOfSatisfying(
            AppException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.LLM_TIMEOUT));
    wire.verify(1, postRequestedFor(urlEqualTo(PATH)));
  }

  @Test
  void authErrorIsNotRetried() throws Exception {
    wire.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(401)));
    assertThatThrownBy(() -> client("bad", "f", 5).generateStructuredJson("sys", "data"))
        .isInstanceOfSatisfying(
            AppException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.LLM_UNAVAILABLE));
    wire.verify(1, postRequestedFor(urlEqualTo(PATH)));
  }

  @Test
  void incompleteAnswerIsReturnedAsEmptyString() throws Exception {
    wire.stubFor(
        post(urlEqualTo(PATH))
            .willReturn(
                okJson(
                    "{\"result\":{\"alternatives\":[{\"message\":{\"role\":\"assistant\",\"text\":\"{\\\"int\"},"
                        + "\"status\":\"ALTERNATIVE_STATUS_TRUNCATED_FINAL\"}]}}")));
    assertThat(client("k", "f", 5).generateStructuredJson("sys", "data")).isEmpty();
  }

  @Test
  void missingCredentialsNeverCallProvider() throws Exception {
    assertThatThrownBy(() -> client("", "", 5).generateStructuredJson("sys", "data"))
        .isInstanceOfSatisfying(
            AppException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.LLM_UNAVAILABLE));
    assertThat(wire.getAllServeEvents()).isEmpty();
  }
}
