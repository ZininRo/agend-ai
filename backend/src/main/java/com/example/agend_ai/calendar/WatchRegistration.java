package com.example.agend_ai.calendar;

import java.time.Instant;

public record WatchRegistration(
    String channelId,
    String resourceId,
    Instant expiration) {}
