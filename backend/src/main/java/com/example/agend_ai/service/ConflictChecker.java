package com.example.agend_ai.service;

import com.example.agend_ai.common.DefaultUser;
import com.example.agend_ai.config.AppProperties;
import com.example.agend_ai.entity.Item;
import com.example.agend_ai.entity.enums.ItemStatus;
import com.example.agend_ai.entity.enums.ItemType;
import com.example.agend_ai.repository.ItemRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ConflictChecker {
  private final ItemRepository items;
  private final AppProperties app;

  public Optional<Item> firstConflict(Item candidate, UUID excludeId) {
    if (candidate.getType() != ItemType.MEETING
        || candidate.getStatus() != ItemStatus.TODO
        || candidate.getStartAt() == null) return Optional.empty();

    Instant start = candidate.getStartAt();
    Instant end = candidate.getEndAt() != null
        ? candidate.getEndAt()
        : start.plus(Duration.ofMinutes(app.meeting().defaultDurationMin()));
    Instant defaultWindowStart = start.minus(Duration.ofMinutes(app.meeting().defaultDurationMin()));

    return items.findMeetingConflicts(
            DefaultUser.ID, start, end, defaultWindowStart, excludeId == null ? new UUID(0L, 0L) : excludeId, ItemStatus.TODO, ItemType.MEETING)
        .stream()
        .findFirst();
  }
}
