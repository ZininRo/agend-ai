package com.example.agend_ai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.agend_ai.service.tts.TtsAudio;
import com.example.agend_ai.service.tts.TtsClient;
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
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TtsSettingsApiTest.TtsTestConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TtsSettingsApiTest extends DatabaseTestSupport {
  @TestConfiguration
  static class TtsTestConfig {
    @Bean
    @Primary
    PreviewTtsClient previewTtsClient() {
      return new PreviewTtsClient();
    }
  }

  static class PreviewTtsClient implements TtsClient {
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
      return new TtsAudio(new byte[] {4, 5, 6}, "audio/ogg");
    }
  }

  @Autowired MockMvc mvc;
  @Autowired PreviewTtsClient tts;

  @BeforeEach
  void reset() {
    tts.lastText.set(null);
    tts.lastVoice.set(null);
  }

  @Test
  void listsOnlyVoicesSupportedByCurrentSpeechKitV1Adapter() throws Exception {
    mvc.perform(get("/api/tts/voices"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.voices.length()").value(7))
        .andExpect(jsonPath("$.voices[0].id").value("jane"))
        .andExpect(jsonPath("$.voices[?(@.id == 'zahar')]").exists())
        .andExpect(jsonPath("$.voices[?(@.id == 'marina')]").exists());
  }

  @Test
  void previewsExplicitVoiceWithoutChangingAccountPreference() throws Exception {
    mvc.perform(
            post("/api/tts/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"voice\":\"ermil\"}"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
        .andExpect(header().string("Content-Type", "audio/ogg"))
        .andExpect(content().bytes(new byte[] {4, 5, 6}));

    assertThat(tts.lastVoice.get()).isEqualTo("ermil");
    assertThat(tts.lastText.get()).contains("AgendAI");
  }

  @Test
  void rejectsVoiceOutsideAllowListBeforeProviderCall() throws Exception {
    mvc.perform(
            post("/api/tts/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"voice\":\"not-a-real-voice\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("REQUEST_INVALID"));

    assertThat(tts.lastText.get()).isNull();
    assertThat(tts.lastVoice.get()).isNull();
  }
}
