package com.example.agend_ai.api;

import com.example.agend_ai.api.dto.MessageRequest;
import com.example.agend_ai.api.dto.MessageResponse;
import com.example.agend_ai.service.IntentDispatcher;
import com.example.agend_ai.service.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/message")
@RequiredArgsConstructor
public class MessageController {
  private final IntentDispatcher intentDispatcher;
  private final RateLimitService rateLimits;

  @PostMapping
  public ResponseEntity<MessageResponse> handleMessage(
      @Valid @RequestBody MessageRequest request, HttpServletRequest http) {
    rateLimits.checkMessage(request.sessionId(), http.getRemoteAddr());
    return ResponseEntity.ok(intentDispatcher.process(request));
  }
}
