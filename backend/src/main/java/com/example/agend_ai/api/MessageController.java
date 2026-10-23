package com.example.agend_ai.api;

import com.example.agend_ai.api.dto.MessageRequest;
import com.example.agend_ai.api.dto.MessageResponse;
import com.example.agend_ai.service.IntentDispatcher;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/message")
@RequiredArgsConstructor
public class MessageController {

    private final IntentDispatcher intentDispatcher;

    @PostMapping
    public ResponseEntity<MessageResponse> handleMessage(@Valid @RequestBody MessageRequest request) {
        MessageResponse response = intentDispatcher.process(request);
        return ResponseEntity.ok(response);
    }
}