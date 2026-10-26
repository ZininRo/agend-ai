package com.example.agend_ai.service;

import com.example.agend_ai.api.error.AppException;
import com.example.agend_ai.api.error.ErrorCode;
import com.example.agend_ai.config.LimitsProperties;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** MVP fixed-window limiter by both session and remote IP. */
@Service
@RequiredArgsConstructor
public class RateLimitService {
  private final LimitsProperties limits;
  private final Clock clock;
  private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

  private record Window(long minute, AtomicInteger count) {}

  public void checkMessage(UUID sessionId, String ip) {
    check("message:session:" + sessionId, limits.rate().messagePerMin());
    check("message:ip:" + safeIp(ip), limits.rate().messagePerMin() * 5);
  }

  public void checkVoice(UUID sessionId, String ip) {
    check("voice:session:" + sessionId, limits.rate().voicePerMin());
    check("voice:ip:" + safeIp(ip), limits.rate().voicePerMin() * 5);
  }

  private void check(String key, int limit) {
    if (limit <= 0) return;
    long nowSec = clock.instant().getEpochSecond();
    long minute = nowSec / 60;
    Window w = windows.compute(key, (k, old) -> {
      if (old == null || old.minute() != minute) return new Window(minute, new AtomicInteger(1));
      old.count().incrementAndGet();
      return old;
    });
    if (w.count().get() > limit) {
      int retry = (int) Math.max(1, 60 - (nowSec % 60));
      throw new AppException(ErrorCode.RATE_LIMITED, retry);
    }
    if (windows.size() > 10_000) windows.entrySet().removeIf(e -> e.getValue().minute() < minute - 1);
  }

  private static String safeIp(String ip) { return ip == null || ip.isBlank() ? "unknown" : ip; }
}
