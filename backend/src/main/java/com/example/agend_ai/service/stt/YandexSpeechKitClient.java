package com.example.agend_ai.service.stt;

import com.example.agend_ai.api.error.AppException;
import com.example.agend_ai.api.error.ErrorCode;
import com.example.agend_ai.config.SttProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.SocketTimeoutException;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Slf4j
@Component
@ConditionalOnProperty(name = "stt.provider", havingValue = "speechkit", matchIfMissing = true)
public class YandexSpeechKitClient implements SttClient {
    private final RestClient client;
    private final SttProperties props;

    public YandexSpeechKitClient(SttProperties props) {
        this.props = props;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(Math.min(5, props.timeoutSec())));
        factory.setReadTimeout(Duration.ofSeconds(props.timeoutSec()));
        this.client = RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory).build();
        if (props.apiKey().isBlank())
            log.warn("SpeechKit не настроен: задайте STT_API_KEY; /api/voice вернёт STT_UNAVAILABLE.");
    }

    @Override
    public SttResult recognize(byte[] oggOpus) {
        if (props.apiKey().isBlank()) throw new AppException(ErrorCode.STT_UNAVAILABLE);
        try {
            JsonNode response =
                    client
                            .post()
                            .uri(
                                    uri ->
                                            uri.path("/speech/v1/stt:recognize")
                                                    .queryParam("lang", "ru-RU")
                                                    .queryParam("topic", "general")
                                                    .queryParam("format", "oggopus")
                                                    .build())
                            .header(HttpHeaders.AUTHORIZATION, "Api-Key " + props.apiKey())
                            .contentType(MediaType.APPLICATION_OCTET_STREAM)
                            .body(oggOpus)
                            .retrieve()
                            .body(JsonNode.class);
            if (response == null) throw new AppException(ErrorCode.STT_UNAVAILABLE);
            String text = response.path("result").asText("").strip();
            // SpeechKit synchronous v1 does not expose confidence. Preserve the common adapter contract:
            // a non-empty provider result is accepted, an empty result is treated as unrecognized.
            return new SttResult(text, text.isEmpty() ? 0.0 : 1.0);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            log.warn("SpeechKit вернул HTTP {}", status);
            throw new AppException(
                    status == 408 || status == 504 ? ErrorCode.STT_TIMEOUT : ErrorCode.STT_UNAVAILABLE);
        } catch (RestClientException e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause())
                if (cause instanceof SocketTimeoutException
                        || cause instanceof java.net.http.HttpTimeoutException) {
                    throw new AppException(ErrorCode.STT_TIMEOUT);
                }
            log.warn("SpeechKit недоступен: {}", e.getClass().getSimpleName());
            throw new AppException(ErrorCode.STT_UNAVAILABLE);
        }
    }
}
