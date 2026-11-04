package com.example.agend_ai.calendar;

import com.example.agend_ai.config.GoogleCalendarProperties;
import com.example.agend_ai.entity.ExternalCalendar;
import com.example.agend_ai.repository.ExternalCalendarRepository;
import java.time.Clock;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CalendarSyncScheduler {
  private final ExternalCalendarRepository calendars;
  private final CalendarSyncWorker worker;
  private final GoogleCalendarProperties properties;
  private final Clock clock;

  @Scheduled(fixedDelayString = "${calendar.google.poll-delay-ms:60000}")
  public void pullChanges() {
    if (!properties.configured()) return;
    try {
      var due =
          calendars.findCalendarsDueForSync(
              clock.instant().minus(Duration.ofMillis(Math.max(1000, properties.pollDelayMs()))));
      int processed = 0;
      for (ExternalCalendar calendar : due) {
        if (processed++ >= 20) break;
        try {
          worker.syncCalendar(calendar.getId());
        } catch (RuntimeException e) {
          log.warn("Calendar pull sync failed for calendar {}: {}", calendar.getId(), e.getClass().getSimpleName());
        }
      }
    } catch (RuntimeException e) {
      log.warn("Calendar sync scheduler failed: {}", e.getClass().getSimpleName());
    }
  }

  @Scheduled(fixedDelayString = "${calendar.google.outbox-delay-ms:3000}")
  public void pushChanges() {
    if (!properties.configured()) return;
    try {
      worker.processOutboxBatch(50);
    } catch (RuntimeException e) {
      log.warn("Calendar outbox scheduler failed: {}", e.getClass().getSimpleName());
    }
  }
}
