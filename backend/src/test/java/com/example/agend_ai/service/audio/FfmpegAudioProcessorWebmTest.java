package com.example.agend_ai.service.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.agend_ai.api.error.AppException;
import com.example.agend_ai.api.error.ErrorCode;
import com.example.agend_ai.config.LimitsProperties;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class FfmpegAudioProcessorWebmTest {
  private final FfmpegAudioProcessor processor = new FfmpegAudioProcessor(
      new LimitsProperties(30, 5_242_880, 1000, new LimitsProperties.Rate(10, 30)));

  @Test
  void acceptsTwentySecondWebmWithoutContainerDuration() throws Exception {
    Assumptions.assumeTrue(ffmpegAvailable());
    byte[] webm = generateWebm(20);
    AudioPayload result = processor.normalize(new MockMultipartFile(
        "audio", "voice.webm", "audio/webm;codecs=opus", webm));
    assertThat(result.durationSeconds()).isBetween(19.5, 20.5);
    assertThat(result.oggOpus()).isNotEmpty();
  }

  @Test
  void rejectsThirtyTwoSecondWebmAsTooLong() throws Exception {
    Assumptions.assumeTrue(ffmpegAvailable());
    byte[] webm = generateWebm(32);
    assertThatThrownBy(() -> processor.normalize(new MockMultipartFile(
        "audio", "voice.webm", "audio/webm", webm)))
        .isInstanceOfSatisfying(AppException.class,
            e -> assertThat(e.getCode()).isEqualTo(ErrorCode.AUDIO_TOO_LONG));
  }

  private static boolean ffmpegAvailable() {
    try {
      Process p = new ProcessBuilder("ffmpeg", "-version").start();
      return p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0;
    } catch (Exception e) { return false; }
  }

  private static byte[] generateWebm(int seconds) throws Exception {
    // Streaming WebM deliberately has no seekable duration in its header.
    Process p = new ProcessBuilder("ffmpeg", "-nostdin", "-v", "error", "-f", "lavfi",
        "-i", "anullsrc=r=16000:cl=mono", "-t", Integer.toString(seconds),
        "-c:a", "libopus", "-f", "webm", "pipe:1").start();
    byte[] bytes = p.getInputStream().readAllBytes();
    if (!p.waitFor(15, TimeUnit.SECONDS) || p.exitValue() != 0) {
      throw new IllegalStateException("Cannot generate test WebM");
    }
    return bytes;
  }
}
