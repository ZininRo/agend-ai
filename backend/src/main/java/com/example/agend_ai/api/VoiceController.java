package com.example.agend_ai.api;

import com.example.agend_ai.api.dto.VoiceResponse;
import com.example.agend_ai.service.RateLimitService;
import com.example.agend_ai.service.VoiceService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
public class VoiceController {
  private final VoiceService voiceService;
  private final RateLimitService rateLimits;

  @PostMapping(value = "/api/voice", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public VoiceResponse transcribe(
      @RequestParam("audio") MultipartFile audio,
      @RequestParam("session_id") UUID sessionId,
      @RequestParam(value = "duration_ms", required = false) Long durationMs,
      HttpServletRequest http) {
    rateLimits.checkVoice(sessionId, http.getRemoteAddr());
    return voiceService.transcribe(audio, sessionId, durationMs);
  }
}
