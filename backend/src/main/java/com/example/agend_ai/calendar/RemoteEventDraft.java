package com.example.agend_ai.calendar;

import java.time.Instant;
import java.time.LocalDate;

public record RemoteEventDraft(
    String summary,
    String description,
    String location,
    Instant startAt,
    Instant endAt,
    LocalDate allDayStart,
    LocalDate allDayEndExclusive,
    String timezone,
    String agendaiItemId) {

  public boolean allDay() {
    return allDayStart != null;
  }
}
