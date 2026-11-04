package com.example.agend_ai.calendar;

import com.example.agend_ai.entity.CalendarConnection;
import com.example.agend_ai.entity.CalendarSyncOutbox;
import com.example.agend_ai.entity.Item;
import com.example.agend_ai.entity.enums.ItemStatus;
import com.example.agend_ai.entity.enums.ItemType;
import com.example.agend_ai.repository.CalendarConnectionRepository;
import com.example.agend_ai.repository.CalendarSyncOutboxRepository;
import com.example.agend_ai.repository.ExternalCalendarRepository;
import com.example.agend_ai.repository.ItemRepository;
import com.example.agend_ai.repository.ItemCalendarLinkRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CalendarOutboxService {
  private final CalendarConnectionRepository connections;
  private final ExternalCalendarRepository calendars;
  private final CalendarSyncOutboxRepository outbox;
  private final ItemRepository items;
  private final ItemCalendarLinkRepository links;
  private final Clock clock;

  @Transactional
  public void enqueue(Item item) {
    if (item == null || item.getId() == null || !eligible(item)) {
      if (item != null && item.getId() != null) outbox.deleteById(item.getId());
      return;
    }
    UUID userId = item.getUser().getId();
    CalendarConnection connection =
        connections.findByUser_IdAndProvider(userId, "GOOGLE").orElse(null);
    if (connection == null || !"CONNECTED".equals(connection.getStatus()) || !connection.isSyncEnabled()) {
      return;
    }
    // Existing links keep syncing to their original Google calendar. A write target is required
    // only for a local AgendAI item that has never been exported before.
    boolean hasExistingLink = links.findByItem_Id(item.getId()).isPresent();
    if (!hasExistingLink
        && calendars.findByConnection_IdAndWriteTargetTrueAndAvailableTrue(connection.getId()).isEmpty()) {
      return;
    }
    Instant now = clock.instant();
    CalendarSyncOutbox row = outbox.findById(item.getId()).orElseGet(CalendarSyncOutbox::new);
    if (row.getItemId() == null) {
      row.setItemId(item.getId());
      row.setUser(item.getUser());
    }
    row.setOperation(
        item.getDeletedAt() != null || item.getStatus() == ItemStatus.CANCELLED ? "DELETE" : "UPSERT");
    row.setAttempts(0);
    row.setAvailableAt(now);
    row.setLastError(null);
    outbox.save(row);
  }

  @Transactional
  public void enqueueExisting(UUID userId) {
    for (Item item : items.findAllActiveByUserId(userId)) {
      if (eligible(item)) enqueue(item);
    }
  }

  @Transactional
  public void clearForUser(UUID userId) {
    outbox.deleteAllByUser_Id(userId);
  }

  private static boolean eligible(Item item) {
    return (item.getType() == ItemType.MEETING || item.getType() == ItemType.REMINDER)
        && item.getStartAt() != null;
  }
}
