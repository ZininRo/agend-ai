package com.example.agend_ai.service;

import static org.assertj.core.api.Assertions.*;

import com.example.agend_ai.api.error.*;
import com.example.agend_ai.config.LimitsProperties;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RateLimitServiceTest {
  @Test
  void messageLimitReturnsRateLimitedWithRetryAfter() {
    var limits = new LimitsProperties(30, 5_242_880, 1000, new LimitsProperties.Rate(10, 2));
    var limiter = new RateLimitService(limits, Clock.fixed(Instant.parse("2026-10-26T12:34:30Z"), ZoneOffset.UTC));
    UUID session = UUID.randomUUID();
    limiter.checkMessage(session, "127.0.0.1");
    limiter.checkMessage(session, "127.0.0.1");
    assertThatThrownBy(() -> limiter.checkMessage(session, "127.0.0.1"))
        .isInstanceOfSatisfying(AppException.class, ex -> {
          assertThat(ex.getCode()).isEqualTo(ErrorCode.RATE_LIMITED);
          assertThat(ex.getRetryAfterSec()).isEqualTo(30);
        });
  }

  @Test
  void differentSessionsHaveIndependentSessionWindows() {
    var limits = new LimitsProperties(30, 5_242_880, 1000, new LimitsProperties.Rate(10, 1));
    var limiter = new RateLimitService(limits, Clock.fixed(Instant.parse("2026-10-26T12:34:00Z"), ZoneOffset.UTC));
    limiter.checkMessage(UUID.randomUUID(), "10.0.0.1");
    assertThatCode(() -> limiter.checkMessage(UUID.randomUUID(), "10.0.0.1")).doesNotThrowAnyException();
  }
}
