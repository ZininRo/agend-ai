package com.example.agend_ai.service.tts;

public record TtsAudio(byte[] bytes, String contentType) {
  public TtsAudio {
    bytes = bytes == null ? new byte[0] : bytes;
    contentType = contentType == null || contentType.isBlank() ? "audio/ogg" : contentType;
  }
}
