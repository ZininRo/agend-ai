package com.example.agend_ai.api;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.agend_ai.entity.enums.MessageRole;
import com.example.agend_ai.repository.ChatSessionRepository;
import com.example.agend_ai.repository.MessageRepository;
import com.example.agend_ai.service.audio.AudioPayload;
import com.example.agend_ai.service.audio.AudioProcessor;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(VoiceApiTest.AudioTestConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class VoiceApiTest extends DatabaseTestSupport {
  static final WireMockServer wire = new WireMockServer(0);

  static {
    wire.start();
  }

  @TestConfiguration
  static class AudioTestConfig {
    @Bean
    @Primary
    AudioProcessor testAudioProcessor() {
      return audio -> new AudioPayload(new byte[] {1, 2, 3}, 1.2);
    }
  }

  @DynamicPropertySource
  static void stt(DynamicPropertyRegistry r) {
    r.add("stt.provider", () -> "speechkit");
    r.add("stt.base-url", wire::baseUrl);
    r.add("stt.api-key", () -> "test-key");
    r.add("stt.timeout-sec", () -> 1);
    r.add("stt.min-confidence", () -> 0.5);
  }

  @AfterAll
  static void stop() {
    wire.stop();
  }

  @Autowired MockMvc mvc;
  @Autowired MessageRepository messages;
  @Autowired ChatSessionRepository sessions;

  @BeforeEach
  void clean() {
    messages.deleteAll();
    sessions.deleteAll();
    wire.resetAll();
  }

  private MockMultipartFile audio() {
    return new MockMultipartFile("audio", "voice.webm", "audio/webm;codecs=opus", new byte[] {9});
  }

  @Test
  void recognizedSpeechReturnsTextAndDoesNotPersistHistory() throws Exception {
    wire.stubFor(post(urlPathEqualTo("/speech/v1/stt:recognize")).willReturn(okJson("{\"result\":\"Назначь встречу завтра\"}")));
    UUID session = UUID.randomUUID();

    mvc.perform(multipart("/api/voice").file(audio()).param("session_id", session.toString()).param("duration_ms", "1200"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.recognized").value(true))
        .andExpect(jsonPath("$.text").value("Назначь встречу завтра"))
        .andExpect(jsonPath("$.confidence").value(1.0))
        .andExpect(jsonPath("$.assistant_message").isEmpty());

    assertThat(messages.count()).isZero();
    assertThat(sessions.count()).isZero();
  }

  @Test
  void emptySpeechReturnsFalseAndPersistsOnlyAssistantMessage() throws Exception {
    wire.stubFor(post(urlPathEqualTo("/speech/v1/stt:recognize")).willReturn(okJson("{\"result\":\"\"}")));
    UUID session = UUID.randomUUID();

    mvc.perform(multipart("/api/voice").file(audio()).param("session_id", session.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.recognized").value(false))
        .andExpect(jsonPath("$.text").value(""))
        .andExpect(jsonPath("$.confidence").value(0.0))
        .andExpect(jsonPath("$.assistant_message").value("Не расслышал вас, повторите, пожалуйста."));

    var stored = messages.findAll();
    assertThat(stored).hasSize(1);
    assertThat(stored.get(0).getRole()).isEqualTo(MessageRole.ASSISTANT);
    assertThat(stored.get(0).getIntent()).isEqualTo("stt_not_recognized");
    assertThat(stored.get(0).getContent()).isEqualTo("Не расслышал вас, повторите, пожалуйста.");
  }

  @Test
  void sttFailureIsNotRetriedOrPersisted() throws Exception {
    wire.stubFor(post(urlPathEqualTo("/speech/v1/stt:recognize")).willReturn(aResponse().withStatus(503)));

    mvc.perform(multipart("/api/voice").file(audio()).param("session_id", UUID.randomUUID().toString()))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error.code").value("STT_UNAVAILABLE"))
        .andExpect(jsonPath("$.error.fallback").value("text_input"));

    wire.verify(1, postRequestedFor(urlPathEqualTo("/speech/v1/stt:recognize")));
    assertThat(messages.count()).isZero();
  }

  @Test
  void missingAudioUsesCatalogError() throws Exception {
    mvc.perform(multipart("/api/voice").param("session_id", UUID.randomUUID().toString()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("AUDIO_MISSING"));
  }

  @Test
  void invalidSessionIsRequestInvalid() throws Exception {
    mvc.perform(multipart("/api/voice").file(audio()).param("session_id", "00000000-0000-0000-0000-000000000001"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("REQUEST_INVALID"));
  }
}
