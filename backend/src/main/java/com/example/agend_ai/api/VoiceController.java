package com.example.agend_ai.api;

import com.example.agend_ai.api.dto.VoiceResponse;
import com.example.agend_ai.service.VoiceService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
public class VoiceController {
    private final VoiceService voiceService;

    @PostMapping(value = "/api/voice", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public VoiceResponse transcribe(
            @RequestPart("audio") MultipartFile audio,
            @RequestParam("session_id") UUID sessionId,
            @RequestParam(value = "duration_ms", required = false) Long durationMs) {
        return voiceService.transcribe(audio, sessionId, durationMs);
    }
}
