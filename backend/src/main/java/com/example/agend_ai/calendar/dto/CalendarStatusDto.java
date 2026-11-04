package com.example.agend_ai.calendar.dto;

import java.time.Instant;
import java.util.List;

public record CalendarStatusDto(
    boolean configured,
    boolean connected,
    String provider,
    String status,
    Instant lastSyncAt,
    String lastError,
    List<ExternalCalendarDto> calendars) {}
