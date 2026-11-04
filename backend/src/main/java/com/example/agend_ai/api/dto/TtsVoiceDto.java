package com.example.agend_ai.api.dto;

import com.example.agend_ai.service.tts.TtsVoice;

public record TtsVoiceDto(String id, String name, String gender) {
  public static TtsVoiceDto from(TtsVoice voice) {
    return new TtsVoiceDto(voice.id(), voice.name(), voice.gender());
  }
}
