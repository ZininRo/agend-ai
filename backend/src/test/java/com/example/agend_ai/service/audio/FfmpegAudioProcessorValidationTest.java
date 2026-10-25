package com.example.agend_ai.service.audio;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.agend_ai.api.error.AppException;
import com.example.agend_ai.api.error.ErrorCode;
import com.example.agend_ai.config.LimitsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class FfmpegAudioProcessorValidationTest {
    private final FfmpegAudioProcessor processor =
            new FfmpegAudioProcessor(new LimitsProperties(30, 5, 1000, new LimitsProperties.Rate(10, 30)));

    @Test
    void emptyAudioIsMissing() {
        assertCode(new MockMultipartFile("audio", new byte[0]), ErrorCode.AUDIO_MISSING);
    }

    @Test
    void oversizedAudioIsRejectedBeforeDecode() {
        assertCode(new MockMultipartFile("audio", "x.webm", "audio/webm", new byte[6]), ErrorCode.AUDIO_TOO_LARGE);
    }

    @Test
    void unsupportedContentTypeIsRejectedBeforeDecode() {
        assertCode(new MockMultipartFile("audio", "x.wav", "audio/wav", new byte[] {1}), ErrorCode.AUDIO_FORMAT_UNSUPPORTED);
    }

    private void assertCode(MockMultipartFile file, ErrorCode code) {
        assertThatThrownBy(() -> processor.normalize(file))
                .isInstanceOfSatisfying(
                        AppException.class,
                        e -> org.assertj.core.api.Assertions.assertThat(e.getCode()).isEqualTo(code));
    }
}
