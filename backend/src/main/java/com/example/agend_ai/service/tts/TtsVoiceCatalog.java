package com.example.agend_ai.service.tts;

import com.example.agend_ai.api.error.AppException;
import com.example.agend_ai.api.error.ErrorCode;
import com.example.agend_ai.config.TtsProperties;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Единый allow-list голосов, которые поддерживает используемый SpeechKit TTS API v1. */
@Slf4j
@Component
public class TtsVoiceCatalog {
  private static final String SAFE_DEFAULT = "jane";
  private static final List<TtsVoice> VOICES =
      List.of(
          new TtsVoice("jane", "Jane", "female"),
          new TtsVoice("alena", "Alena", "female"),
          new TtsVoice("marina", "Marina", "female"),
          new TtsVoice("omazh", "Omazh", "female"),
          new TtsVoice("filipp", "Filipp", "male"),
          new TtsVoice("ermil", "Ermil", "male"),
          new TtsVoice("zahar", "Zahar", "male"));

  private final String defaultVoice;

  public TtsVoiceCatalog(TtsProperties properties) {
    String configured = normalize(properties.voice());
    if (isSupported(configured)) {
      defaultVoice = configured;
    } else {
      defaultVoice = SAFE_DEFAULT;
      log.warn(
          "TTS_VOICE '{}' не поддерживается текущим SpeechKit API v1; используется '{}'.",
          properties.voice(),
          SAFE_DEFAULT);
    }
  }

  public List<TtsVoice> all() {
    return VOICES;
  }

  public String defaultVoice() {
    return defaultVoice;
  }

  /** Сохранённое значение может быть null у аккаунтов, созданных до появления настройки. */
  public String resolve(String storedVoice) {
    String normalized = normalize(storedVoice);
    return isSupported(normalized) ? normalized : defaultVoice;
  }

  /** Значение от клиента обязано быть одним из разрешённых голосов. */
  public String requireSupported(String requestedVoice) {
    String normalized = normalize(requestedVoice);
    if (!isSupported(normalized)) throw new AppException(ErrorCode.REQUEST_INVALID);
    return normalized;
  }

  private static boolean isSupported(String voice) {
    return VOICES.stream().anyMatch(candidate -> candidate.id().equals(voice));
  }

  private static String normalize(String voice) {
    return voice == null ? "" : voice.strip().toLowerCase(Locale.ROOT);
  }
}
