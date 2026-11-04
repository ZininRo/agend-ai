package com.example.agend_ai.repository;

import com.example.agend_ai.entity.ItemCalendarLink;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItemCalendarLinkRepository extends JpaRepository<ItemCalendarLink, UUID> {
  Optional<ItemCalendarLink> findByItem_Id(UUID itemId);
  Optional<ItemCalendarLink> findByExternalCalendar_IdAndExternalEventIdHash(UUID calendarId, String eventHash);
  List<ItemCalendarLink> findAllByExternalCalendar_Id(UUID calendarId);
}
