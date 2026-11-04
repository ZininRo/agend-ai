package com.example.agend_ai.calendar;

import java.time.Instant;
import java.time.LocalDate;

public record RemoteEvent(
    String id,
    String etag,
    String status,
    String summary,
    String description,
    String location,
    Instant startAt,
    Instant endAt,
    LocalDate allDayStart,
    LocalDate allDayEndExclusive,
    String timezone,
    Instant updatedAt,
    String agendaiItemId) {

  public boolean cancelled() {
    return "cancelled".equalsIgnoreCase(status);
  }

  public boolean allDay() {
    return allDayStart != null;
  }
}
