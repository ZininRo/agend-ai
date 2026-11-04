package com.example.agend_ai.service.tts;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.example.agend_ai.api.error.AppException;
import com.example.agend_ai.api.error.ErrorCode;
import com.example.agend_ai.config.SttProperties;
import com.example.agend_ai.config.TtsProperties;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;

class YandexSpeechKitTtsClientTest {
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

  private YandexSpeechKitTtsClient client(String ttsKey, String sttKey, int timeoutSec) {
    return new YandexSpeechKitTtsClient(
        new TtsProperties(
            "speechkit", ttsKey, wire.baseUrl(), timeoutSec, "jane", "", 1.0, 400),
        new SttProperties("speechkit", sttKey, wire.baseUrl(), 5, 0.5));
  }

  @Test
  void sendsFormEncodedRequestAndReturnsOgg() {
    byte[] expected = new byte[] {1, 2, 3, 4};
    wire.stubFor(
        post(urlEqualTo("/speech/v1/tts:synthesize"))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "audio/ogg").withBody(expected)));

    TtsAudio result =
        client("tts-key", "stt-key", 5)
            .synthesize("Готово, встреча завтра в десять.", "zahar");

    assertThat(result.bytes()).containsExactly(expected);
    assertThat(result.contentType()).isEqualTo("audio/ogg");
    wire.verify(
        1,
        postRequestedFor(urlEqualTo("/speech/v1/tts:synthesize"))
            .withHeader("Authorization", equalTo("Api-Key tts-key"))
            .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
            .withRequestBody(containing("lang=ru-RU"))
            .withRequestBody(containing("voice=zahar"))
            .withRequestBody(containing("format=oggopus"))
            .withRequestBody(containing("speed=1.0")));
  }

  @Test
  void fallsBackToSttApiKeyWhenDedicatedTtsKeyIsEmpty() {
    wire.stubFor(
        post(urlEqualTo("/speech/v1/tts:synthesize"))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "audio/ogg").withBody(new byte[] {7})));

    client("", "shared-key", 5).synthesize("Привет");

    wire.verify(
        postRequestedFor(urlEqualTo("/speech/v1/tts:synthesize"))
            .withHeader("Authorization", equalTo("Api-Key shared-key")));
  }

  @Test
  void providerFailureIsNotRetried() {
    wire.stubFor(
        post(urlEqualTo("/speech/v1/tts:synthesize")).willReturn(aResponse().withStatus(503)));

    assertThatThrownBy(() -> client("key", "", 5).synthesize("Привет"))
        .isInstanceOfSatisfying(
            AppException.class,
            e -> assertThat(e.getCode()).isEqualTo(ErrorCode.TTS_UNAVAILABLE));
    wire.verify(1, postRequestedFor(urlEqualTo("/speech/v1/tts:synthesize")));
  }

  @Test
  void gatewayTimeoutMapsToTtsTimeout() {
    wire.stubFor(
        post(urlEqualTo("/speech/v1/tts:synthesize")).willReturn(aResponse().withStatus(504)));

    assertThatThrownBy(() -> client("key", "", 5).synthesize("Привет"))
        .isInstanceOfSatisfying(
            AppException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.TTS_TIMEOUT));
  }

  @Test
  void missingBothKeysMapsToUnavailableWithoutRequest() {
    assertThatThrownBy(() -> client("", "", 5).synthesize("Привет"))
        .isInstanceOfSatisfying(
            AppException.class,
            e -> assertThat(e.getCode()).isEqualTo(ErrorCode.TTS_UNAVAILABLE));
    wire.verify(0, postRequestedFor(anyUrl()));
  }
}
