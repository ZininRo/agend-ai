package com.example.agend_ai.service.stt;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.example.agend_ai.api.error.AppException;
import com.example.agend_ai.api.error.ErrorCode;
import com.example.agend_ai.config.SttProperties;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;

class YandexSpeechKitClientTest {
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

    private YandexSpeechKitClient client(String key, int timeoutSec) {
        return new YandexSpeechKitClient(
                new SttProperties("speechkit", key, wire.baseUrl(), timeoutSec, 0.5));
    }

    @Test
    void sendsOggOpusAndReturnsRecognizedText() {
        wire.stubFor(post(urlPathEqualTo("/speech/v1/stt:recognize")).willReturn(okJson("{\"result\":\"Привет мир\"}")));

        SttResult result = client("key", 5).recognize(new byte[] {1, 2, 3});

        assertThat(result.text()).isEqualTo("Привет мир");
        assertThat(result.confidence()).isEqualTo(1.0);
        wire.verify(
                1,
                postRequestedFor(urlPathEqualTo("/speech/v1/stt:recognize"))
                        .withHeader("Authorization", equalTo("Api-Key key"))
                        .withQueryParam("lang", equalTo("ru-RU"))
                        .withQueryParam("topic", equalTo("general"))
                        .withQueryParam("format", equalTo("oggopus")));
    }

    @Test
    void emptyProviderResultIsUnrecognized() {
        wire.stubFor(post(urlPathEqualTo("/speech/v1/stt:recognize")).willReturn(okJson("{\"result\":\"\"}")));
        assertThat(client("key", 5).recognize(new byte[] {1}).confidence()).isZero();
    }

    @Test
    void providerFailureIsNotRetried() {
        wire.stubFor(post(urlPathEqualTo("/speech/v1/stt:recognize")).willReturn(aResponse().withStatus(503)));
        assertThatThrownBy(() -> client("key", 5).recognize(new byte[] {1}))
                .isInstanceOfSatisfying(
                        AppException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.STT_UNAVAILABLE));
        wire.verify(1, postRequestedFor(urlPathEqualTo("/speech/v1/stt:recognize")));
    }

    @Test
    void gatewayTimeoutMapsToSttTimeout() {
        wire.stubFor(post(urlPathEqualTo("/speech/v1/stt:recognize")).willReturn(aResponse().withStatus(504)));
        assertThatThrownBy(() -> client("key", 5).recognize(new byte[] {1}))
                .isInstanceOfSatisfying(
                        AppException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.STT_TIMEOUT));
    }

    @Test
    void missingKeyMapsToUnavailableWithoutRequest() {
        assertThatThrownBy(() -> client("", 5).recognize(new byte[] {1}))
                .isInstanceOfSatisfying(
                        AppException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.STT_UNAVAILABLE));
        wire.verify(0, postRequestedFor(anyUrl()));
    }
}
