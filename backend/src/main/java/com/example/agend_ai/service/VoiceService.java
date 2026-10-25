package com.example.agend_ai.service;

import com.example.agend_ai.api.dto.VoiceResponse;
import com.example.agend_ai.api.error.AppException;
import com.example.agend_ai.api.error.ErrorCode;
import com.example.agend_ai.common.DefaultUser;
import com.example.agend_ai.config.SttProperties;
import com.example.agend_ai.entity.Message;
import com.example.agend_ai.entity.enums.MessageRole;
import com.example.agend_ai.repository.ChatSessionRepository;
import com.example.agend_ai.repository.MessageRepository;
import com.example.agend_ai.repository.UserRepository;
import com.example.agend_ai.service.audio.AudioProcessor;
import com.example.agend_ai.service.stt.SttClient;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class VoiceService {
    static final String NOT_RECOGNIZED = "Не расслышал вас, повторите, пожалуйста.";

    private final AudioProcessor audioProcessor;
    private final SttClient sttClient;
    private final SttProperties sttProperties;
    private final ChatSessionRepository sessions;
    private final MessageRepository messages;
    private final UserRepository users;

    @Transactional
    public VoiceResponse transcribe(MultipartFile audio, UUID sessionId, Long durationMs) {
        if (sessionId == null || sessionId.version() != 4 || sessionId.variant() != 2)
            throw new AppException(ErrorCode.REQUEST_INVALID);
        if (durationMs != null && durationMs < 0) throw new AppException(ErrorCode.REQUEST_INVALID);

        var normalized = audioProcessor.normalize(audio);
        var result = sttClient.recognize(normalized.oggOpus());
        boolean recognized =
                !result.text().isBlank() && result.confidence() >= sttProperties.minConfidence();
        if (recognized) return new VoiceResponse(true, result.text(), result.confidence(), null);

        // Only the assistant's failed-recognition message is persisted. No user/audio content is stored.
        sessions.ensure(sessionId, DefaultUser.ID);
        var session = sessions.findByIdForUpdate(sessionId).orElseThrow();
        Message assistant = new Message();
        assistant.setUser(users.getReferenceById(DefaultUser.ID));
        assistant.setSession(session);
        assistant.setRole(MessageRole.ASSISTANT);
        assistant.setContent(NOT_RECOGNIZED);
        assistant.setIntent("stt_not_recognized");
        messages.save(assistant);
        return new VoiceResponse(false, "", result.confidence(), NOT_RECOGNIZED);
    }
}
