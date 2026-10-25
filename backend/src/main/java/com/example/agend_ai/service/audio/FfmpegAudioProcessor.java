package com.example.agend_ai.service.audio;

import com.example.agend_ai.api.error.AppException;
import com.example.agend_ai.api.error.ErrorCode;
import com.example.agend_ai.config.LimitsProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
@RequiredArgsConstructor
public class FfmpegAudioProcessor implements AudioProcessor {
    private static final Set<String> SUPPORTED =
            Set.of("audio/webm", "audio/ogg", "audio/mp4", "video/webm", "video/mp4");
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(8);
    private final LimitsProperties limits;

    @Override
    public AudioPayload normalize(MultipartFile audio) {
        if (audio == null || audio.isEmpty()) throw new AppException(ErrorCode.AUDIO_MISSING);
        if (audio.getSize() > limits.maxAudioBytes()) throw new AppException(ErrorCode.AUDIO_TOO_LARGE);
        String contentType = baseContentType(audio.getContentType());
        if (!SUPPORTED.contains(contentType)) throw new AppException(ErrorCode.AUDIO_FORMAT_UNSUPPORTED);

        final byte[] source;
        try {
            source = audio.getBytes();
        } catch (IOException e) {
            throw new AppException(ErrorCode.AUDIO_FORMAT_UNSUPPORTED);
        }

        double duration = probeDuration(source);
        if (!Double.isFinite(duration) || duration <= 0) throw new AppException(ErrorCode.AUDIO_FORMAT_UNSUPPORTED);
        if (duration > limits.maxAudioSec() + 0.05) throw new AppException(ErrorCode.AUDIO_TOO_LONG);

        byte[] normalized = transcode(source);
        if (normalized.length == 0) throw new AppException(ErrorCode.AUDIO_FORMAT_UNSUPPORTED);
        return new AudioPayload(normalized, duration);
    }

    private static String baseContentType(String raw) {
        if (raw == null) return "";
        int semicolon = raw.indexOf(';');
        return (semicolon >= 0 ? raw.substring(0, semicolon) : raw).strip().toLowerCase(Locale.ROOT);
    }

    private double probeDuration(byte[] source) {
        Process process = start(
                "ffprobe", "-v", "error", "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1", "pipe:0");
        ProcessOutput out = communicate(process, source);
        if (out.exitCode() != 0) throw new AppException(ErrorCode.AUDIO_FORMAT_UNSUPPORTED);
        try {
            return Double.parseDouble(new String(out.stdout(), StandardCharsets.UTF_8).strip());
        } catch (NumberFormatException e) {
            throw new AppException(ErrorCode.AUDIO_FORMAT_UNSUPPORTED);
        }
    }

    private byte[] transcode(byte[] source) {
        Process process = start(
                "ffmpeg", "-v", "error", "-i", "pipe:0", "-vn", "-ac", "1",
                "-c:a", "libopus", "-b:a", "32k", "-f", "ogg", "pipe:1");
        ProcessOutput out = communicate(process, source);
        if (out.exitCode() != 0) throw new AppException(ErrorCode.AUDIO_FORMAT_UNSUPPORTED);
        return out.stdout();
    }

    private static Process start(String... command) {
        try {
            return new ProcessBuilder(command).start();
        } catch (IOException e) {
            throw new IllegalStateException("ffmpeg/ffprobe не установлен в окружении приложения", e);
        }
    }

    private static ProcessOutput communicate(Process process, byte[] input) {
        try {
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            Thread outThread = new Thread(() -> copy(process.getInputStream(), stdout), "ffmpeg-stdout");
            Thread errThread = new Thread(() -> copy(process.getErrorStream(), stderr), "ffmpeg-stderr");
            outThread.start();
            errThread.start();
            try (var stdin = process.getOutputStream()) {
                stdin.write(input);
            }
            if (!process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new AppException(ErrorCode.AUDIO_FORMAT_UNSUPPORTED);
            }
            outThread.join();
            errThread.join();
            return new ProcessOutput(process.exitValue(), stdout.toByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new AppException(ErrorCode.AUDIO_FORMAT_UNSUPPORTED);
        } catch (IOException e) {
            process.destroyForcibly();
            throw new AppException(ErrorCode.AUDIO_FORMAT_UNSUPPORTED);
        }
    }

    private static void copy(java.io.InputStream in, ByteArrayOutputStream out) {
        try (in) {
            in.transferTo(out);
        } catch (IOException ignored) {
            // The process exit code is the authoritative conversion result.
        }
    }

    private record ProcessOutput(int exitCode, byte[] stdout) {}
}
