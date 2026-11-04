package com.example.agend_ai.service;

import com.example.agend_ai.api.error.AppException;
import com.example.agend_ai.api.error.ErrorCode;
import com.example.agend_ai.config.TtsProperties;
import com.example.agend_ai.entity.enums.MessageRole;
import com.example.agend_ai.repository.MessageRepository;
import com.example.agend_ai.repository.UserRepository;
import com.example.agend_ai.security.CurrentUser;
import com.example.agend_ai.service.tts.TtsAudio;
import com.example.agend_ai.service.tts.TtsClient;
import com.example.agend_ai.service.tts.TtsVoiceCatalog;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MessageSpeechService {
  private final MessageRepository messages;
  private final UserRepository users;
  private final CurrentUser currentUser;
  private final TtsClient ttsClient;
  private final TtsProperties properties;
  private final TtsVoiceCatalog voices;

  public TtsAudio synthesize(UUID messageId) {
    if (messageId == null) throw new AppException(ErrorCode.REQUEST_INVALID);
    UUID userId = currentUser.id();
    var message =
        messages
            .findByIdAndUserId(messageId, userId)
            .orElseThrow(() -> new AppException(ErrorCode.REQUEST_INVALID));
    if (message.getRole() != MessageRole.ASSISTANT) {
      throw new AppException(ErrorCode.REQUEST_INVALID);
    }

    String source = message.getSpeechContent();
    if (source == null || source.isBlank()) source = message.getContent();
    String text = prepareForSpeech(source, properties.maxChars());
    if (text.isBlank()) throw new AppException(ErrorCode.REQUEST_INVALID);

    String voice =
        users.findById(userId)
            .map(user -> voices.resolve(user.getTtsVoice()))
            .orElseThrow(() -> new AppException(ErrorCode.AUTH_REQUIRED));
    return ttsClient.synthesize(text, voice);
  }

  static String prepareForSpeech(String text, int maxChars) {
    if (text == null) return "";
    String normalized =
        text.replaceAll("(?m)^\\s*\\d{1,2}[.)]\\s*", "")
            .replaceAll("[«»\"“”]", "")
            .replaceAll("\\s*[—–]\\s*", ", ")
            .replaceAll("\\s*\\R+\\s*", ". ")
            .replaceAll("\\s+", " ")
            .strip();
    int limit = Math.max(1, maxChars);
    if (normalized.length() <= limit) return normalized;

    int cut = Math.min(limit, normalized.length());
    int sentence =
        Math.max(
            normalized.lastIndexOf('.', cut),
            Math.max(normalized.lastIndexOf('!', cut), normalized.lastIndexOf('?', cut)));
    if (sentence >= limit / 2) return normalized.substring(0, sentence + 1).strip();
    int word = normalized.lastIndexOf(' ', cut);
    if (word >= limit / 2) cut = word;
    return normalized.substring(0, cut).strip();
  }
}
