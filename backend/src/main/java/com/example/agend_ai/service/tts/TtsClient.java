package com.example.agend_ai.service.tts;

public interface TtsClient {
  TtsAudio synthesize(String text);

  /**
   * Синтез с явным голосом. Default сохраняет совместимость с другими адаптерами; провайдеры,
   * поддерживающие выбор голоса, переопределяют этот метод.
   */
  default TtsAudio synthesize(String text, String voice) {
    return synthesize(text);
  }
}
