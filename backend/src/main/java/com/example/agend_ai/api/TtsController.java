package com.example.agend_ai.api;

import com.example.agend_ai.api.dto.TtsPreviewRequest;
import com.example.agend_ai.api.dto.TtsVoiceDto;
import com.example.agend_ai.api.dto.TtsVoicesResponse;
import com.example.agend_ai.service.tts.TtsClient;
import com.example.agend_ai.service.tts.TtsVoiceCatalog;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tts")
@RequiredArgsConstructor
public class TtsController {
  private static final String PREVIEW_TEXT =
      "Привет! Я AgendAI. Так будет звучать мой голос.";

  private final TtsVoiceCatalog voices;
  private final TtsClient ttsClient;

  @GetMapping("/voices")
  public TtsVoicesResponse voices() {
    return new TtsVoicesResponse(voices.all().stream().map(TtsVoiceDto::from).toList());
  }

  @PostMapping(value = "/preview", produces = "audio/ogg")
  public ResponseEntity<byte[]> preview(@Valid @RequestBody TtsPreviewRequest request) {
    String voice = voices.requireSupported(request.voice());
    var audio = ttsClient.synthesize(PREVIEW_TEXT, voice);
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue())
        .contentType(MediaType.parseMediaType(audio.contentType()))
        .body(audio.bytes());
  }
}
