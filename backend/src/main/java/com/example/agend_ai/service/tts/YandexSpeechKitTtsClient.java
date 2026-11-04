package com.example.agend_ai.service.tts;

import com.example.agend_ai.api.error.AppException;
import com.example.agend_ai.api.error.ErrorCode;
import com.example.agend_ai.config.SttProperties;
import com.example.agend_ai.config.TtsProperties;
import java.net.SocketTimeoutException;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Slf4j
@Component
@ConditionalOnProperty(name = "tts.provider", havingValue = "speechkit", matchIfMissing = true)
public class YandexSpeechKitTtsClient implements TtsClient {
  private static final String OGG_CONTENT_TYPE = "audio/ogg";

  private final RestClient client;
  private final TtsProperties props;
  private final String apiKey;

  public YandexSpeechKitTtsClient(TtsProperties props, SttProperties sttProperties) {
    this.props = props;
    this.apiKey = firstNonBlank(props.apiKey(), sttProperties.apiKey());
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(Math.min(5, props.timeoutSec())));
    factory.setReadTimeout(Duration.ofSeconds(props.timeoutSec()));
    this.client = RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory).build();
    if (apiKey.isBlank()) {
      log.warn(
          "SpeechKit TTS не настроен: задайте TTS_API_KEY или STT_API_KEY; серверная озвучка будет недоступна.");
    }
  }

  @Override
  public TtsAudio synthesize(String text) {
    return synthesize(text, props.voice());
  }

  @Override
  public TtsAudio synthesize(String text, String voice) {
    if (apiKey.isBlank()) throw new AppException(ErrorCode.TTS_UNAVAILABLE);
    if (text == null || text.isBlank()) throw new AppException(ErrorCode.REQUEST_INVALID);

    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("text", text);
    form.add("lang", "ru-RU");
    String effectiveVoice =
        voice == null || voice.isBlank() ? props.voice() : voice.strip();
    form.add("voice", effectiveVoice);
    form.add("format", "oggopus");
    form.add("speed", Double.toString(props.speed()));
    if (props.emotion() != null && !props.emotion().isBlank()) {
      form.add("emotion", props.emotion().strip());
    }

    try {
      byte[] audio =
          client
              .post()
              .uri("/speech/v1/tts:synthesize")
              .header(HttpHeaders.AUTHORIZATION, "Api-Key " + apiKey)
              .contentType(MediaType.APPLICATION_FORM_URLENCODED)
              .accept(MediaType.parseMediaType(OGG_CONTENT_TYPE), MediaType.APPLICATION_OCTET_STREAM)
              .body(form)
              .retrieve()
              .body(byte[].class);
      if (audio == null || audio.length == 0) throw new AppException(ErrorCode.TTS_UNAVAILABLE);
      return new TtsAudio(audio, OGG_CONTENT_TYPE);
    } catch (RestClientResponseException e) {
      int status = e.getStatusCode().value();
      log.warn("SpeechKit TTS вернул HTTP {}", status);
      throw new AppException(
          status == 408 || status == 504 ? ErrorCode.TTS_TIMEOUT : ErrorCode.TTS_UNAVAILABLE);
    } catch (RestClientException e) {
      for (Throwable cause = e; cause != null; cause = cause.getCause()) {
        if (cause instanceof SocketTimeoutException
            || cause instanceof java.net.http.HttpTimeoutException) {
          throw new AppException(ErrorCode.TTS_TIMEOUT);
        }
      }
      log.warn("SpeechKit TTS недоступен: {}", e.getClass().getSimpleName());
      throw new AppException(ErrorCode.TTS_UNAVAILABLE);
    }
  }

  private static String firstNonBlank(String primary, String fallback) {
    if (primary != null && !primary.isBlank()) return primary.strip();
    return fallback == null ? "" : fallback.strip();
  }
}
