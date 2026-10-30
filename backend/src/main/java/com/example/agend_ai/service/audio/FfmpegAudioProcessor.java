package com.example.agend_ai.service.audio;

import com.example.agend_ai.api.error.AppException;
import com.example.agend_ai.api.error.ErrorCode;
import com.example.agend_ai.config.LimitsProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
  private static final Set<String> SUPPORTED = Set.of(
      "audio/webm", "audio/ogg", "audio/mp4", "video/webm", "video/mp4",
      "audio/x-m4a", "audio/m4a", "audio/aac");
  private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(25);
  private final LimitsProperties limits;

  @Override
  public AudioPayload normalize(MultipartFile audio) {
    if (audio == null || audio.isEmpty()) throw new AppException(ErrorCode.AUDIO_MISSING);
    if (audio.getSize() > limits.maxAudioBytes()) throw new AppException(ErrorCode.AUDIO_TOO_LARGE);
    String type = baseContentType(audio.getContentType());
    if (!SUPPORTED.contains(type)) throw new AppException(ErrorCode.AUDIO_FORMAT_UNSUPPORTED);

    Path input = null;
    try {
      // A seekable file is important for WebM recordings without a duration header
      // and fragmented MP4 produced by Safari. Never use ffprobe on pipe:0 here.
      input = Files.createTempFile("agendai-voice-", ".input");
      Files.write(input, audio.getBytes());
      double duration = probeDuration(input);
      if (!Double.isFinite(duration) || duration <= 0) {
        duration = decodedDuration(input);
      }
      if (!Double.isFinite(duration) || duration <= 0) {
        throw new AppException(ErrorCode.AUDIO_FORMAT_UNSUPPORTED);
      }
      if (duration > limits.maxAudioSec() + 0.05) throw new AppException(ErrorCode.AUDIO_TOO_LONG);

      ProcessOutput encoded = run("ffmpeg", "-nostdin", "-v", "error", "-i", input.toString(),
          "-vn", "-ac", "1", "-c:a", "libopus", "-b:a", "32k", "-f", "ogg", "pipe:1");
      if (encoded.exitCode() != 0 || encoded.stdout().length == 0) {
        throw new AppException(ErrorCode.AUDIO_FORMAT_UNSUPPORTED);
      }
      return new AudioPayload(encoded.stdout(), duration);
    } catch (IOException e) {
      throw new AppException(ErrorCode.AUDIO_FORMAT_UNSUPPORTED);
    } finally {
      if (input != null) {
        try { Files.deleteIfExists(input); } catch (IOException ignored) { }
      }
    }
  }

  private static String baseContentType(String raw) {
    if (raw == null) return "";
    int semicolon = raw.indexOf(';');
    return (semicolon >= 0 ? raw.substring(0, semicolon) : raw).strip().toLowerCase(Locale.ROOT);
  }

  private static double probeDuration(Path input) {
    ProcessOutput out = run("ffprobe", "-v", "error", "-show_entries", "format=duration",
        "-of", "default=noprint_wrappers=1:nokey=1", input.toString());
    if (out.exitCode() != 0) return Double.NaN;
    try {
      return Double.parseDouble(new String(out.stdout(), StandardCharsets.UTF_8).strip());
    } catch (NumberFormatException e) {
      // WebM recordings frequently report N/A for format duration.
      return Double.NaN;
    }
  }

  private double decodedDuration(Path input) {
    // Decode at a fixed sample rate to determine the real audio duration when
    // container metadata is missing. Decode at most maxAudioSec + 1 seconds:
    // this keeps the output bounded and still detects overlong recordings.
    int decodeSeconds = Math.max(1, limits.maxAudioSec() + 1);
    ProcessOutput out = run("ffmpeg", "-nostdin", "-v", "error", "-i", input.toString(),
        "-vn", "-t", Integer.toString(decodeSeconds), "-ac", "1", "-ar", "16000",
        "-f", "s16le", "pipe:1");
    if (out.exitCode() != 0 || out.stdout().length == 0) return Double.NaN;
    return out.stdout().length / 32000.0;
  }

  private static ProcessOutput run(String... command) {
    Process process;
    try {
      process = new ProcessBuilder(command).start();
    } catch (IOException e) {
      throw new IllegalStateException("ffmpeg/ffprobe не установлен в окружении приложения", e);
    }
    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    Thread reader = new Thread(() -> {
      try (var stream = process.getInputStream()) { stream.transferTo(stdout); }
      catch (IOException ignored) { }
    }, "agendai-ffmpeg-output");
    reader.setDaemon(true);
    reader.start();
    try {
      // ffmpeg -v error may write diagnostics. Drain stderr to prevent pipe deadlocks,
      // but never log it: filenames and audio metadata can contain private details.
      Thread errors = new Thread(() -> {
        try (var stream = process.getErrorStream()) { stream.transferTo(java.io.OutputStream.nullOutputStream()); }
        catch (IOException ignored) { }
      }, "agendai-ffmpeg-errors");
      errors.setDaemon(true);
      errors.start();
      process.getOutputStream().close();
      if (!process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        process.waitFor();
        throw new AppException(ErrorCode.AUDIO_FORMAT_UNSUPPORTED);
      }
      reader.join();
      errors.join();
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

  private record ProcessOutput(int exitCode, byte[] stdout) { }
}
