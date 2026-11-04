package com.example.agend_ai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.agend_ai.common.DefaultUser;
import com.example.agend_ai.entity.ChatSession;
import com.example.agend_ai.entity.Message;
import com.example.agend_ai.entity.User;
import com.example.agend_ai.entity.enums.MessageRole;
import com.example.agend_ai.repository.ChatSessionRepository;
import com.example.agend_ai.repository.MessageRepository;
import com.example.agend_ai.repository.UserRepository;
import com.example.agend_ai.service.tts.TtsAudio;
import com.example.agend_ai.service.tts.TtsClient;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(MessageSpeechApiTest.TtsTestConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MessageSpeechApiTest extends DatabaseTestSupport {
  @TestConfiguration
  static class TtsTestConfig {
    @Bean
    @Primary
    RecordingTtsClient recordingTtsClient() {
      return new RecordingTtsClient();
    }
  }

  static class RecordingTtsClient implements TtsClient {
    final AtomicReference<String> lastText = new AtomicReference<>();
    final AtomicReference<String> lastVoice = new AtomicReference<>();

    @Override
    public TtsAudio synthesize(String text) {
      return synthesize(text, null);
    }

    @Override
    public TtsAudio synthesize(String text, String voice) {
      lastText.set(text);
      lastVoice.set(voice);
      return new TtsAudio(new byte[] {11, 22, 33}, "audio/ogg");
    }
  }

  @Autowired MockMvc mvc;
  @Autowired UserRepository users;
  @Autowired ChatSessionRepository sessions;
  @Autowired MessageRepository messages;
  @Autowired RecordingTtsClient tts;

  @BeforeEach
  void clean() {
    messages.deleteAll();
    sessions.deleteAll();
    tts.lastText.set(null);
    tts.lastVoice.set(null);
    users.findById(DefaultUser.ID).ifPresent(user -> {
      user.setTtsVoice(null);
      users.saveAndFlush(user);
    });
  }

  @Test
  void returnsPrivateOggForOwnedAssistantMessageAndUsesSpeechContent() throws Exception {
    Message message = assistant(DefaultUser.ID, "Полный текст ответа", "1) «Коротко» — завтра\nв 10:00");

    mvc.perform(get("/api/messages/{id}/speech", message.getId()))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "audio/ogg"))
        .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
        .andExpect(content().bytes(new byte[] {11, 22, 33}));

    assertThat(tts.lastText.get()).isEqualTo("Коротко, завтра. в 10:00");
    assertThat(tts.lastVoice.get()).isEqualTo("jane");
  }

  @Test
  void usesVoiceSavedInUserAccount() throws Exception {
    User user = users.findById(DefaultUser.ID).orElseThrow();
    user.setTtsVoice("zahar");
    users.saveAndFlush(user);
    Message message = assistant(DefaultUser.ID, "Готово", "Готово");

    mvc.perform(get("/api/messages/{id}/speech", message.getId())).andExpect(status().isOk());

    assertThat(tts.lastVoice.get()).isEqualTo("zahar");
  }

  @Test
  void oldMessageWithoutSpeechContentFallsBackToMessageContent() throws Exception {
    Message message = assistant(DefaultUser.ID, "Готово: задача создана.", null);

    mvc.perform(get("/api/messages/{id}/speech", message.getId())).andExpect(status().isOk());

    assertThat(tts.lastText.get()).isEqualTo("Готово: задача создана.");
  }

  @Test
  void anotherUsersMessageIsNotExposed() throws Exception {
    User other = new User();
    other.setDisplayName("other");
    other = users.saveAndFlush(other);
    Message message = assistant(other.getId(), "Секрет", "Секрет");

    mvc.perform(get("/api/messages/{id}/speech", message.getId()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("REQUEST_INVALID"));

    assertThat(tts.lastText.get()).isNull();
  }

  private Message assistant(UUID userId, String content, String speechContent) {
    User user = users.findById(userId).orElseThrow();
    ChatSession session = new ChatSession();
    session.setId(UUID.randomUUID());
    session.setUser(user);
    session = sessions.saveAndFlush(session);

    Message message = new Message();
    message.setUser(user);
    message.setSession(session);
    message.setRole(MessageRole.ASSISTANT);
    message.setContent(content);
    message.setSpeechContent(speechContent);
    message.setTimezone("Europe/Moscow");
    return messages.saveAndFlush(message);
  }
}
