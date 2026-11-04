package com.example.agend_ai.calendar.dto;

import com.example.agend_ai.entity.ExternalCalendar;
import java.time.Instant;
import java.util.UUID;

public record ExternalCalendarDto(
    UUID id,
    String name,
    String timezone,
    String accessRole,
    boolean primary,
    boolean writable,
    boolean syncEnabled,
    boolean writeTarget,
    Instant lastSyncAt) {

  public static ExternalCalendarDto from(ExternalCalendar calendar) {
    return new ExternalCalendarDto(
        calendar.getId(),
        calendar.getSummary(),
        calendar.getTimezone(),
        calendar.getAccessRole(),
        calendar.isPrimaryCalendar(),
        calendar.writable(),
        calendar.isSyncEnabled(),
        calendar.isWriteTarget(),
        calendar.getLastSyncAt());
  }
}
