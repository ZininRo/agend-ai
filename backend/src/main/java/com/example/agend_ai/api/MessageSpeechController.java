package com.example.agend_ai.api;

import com.example.agend_ai.service.MessageSpeechService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class MessageSpeechController {
  private final MessageSpeechService speechService;

  @GetMapping("/api/messages/{messageId}/speech")
  public ResponseEntity<byte[]> speech(@PathVariable UUID messageId) {
    var audio = speechService.synthesize(messageId);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(audio.contentType()))
        .cacheControl(CacheControl.noStore())
        .contentLength(audio.bytes().length)
        .body(audio.bytes());
  }
}
