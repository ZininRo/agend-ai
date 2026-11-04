package com.example.agend_ai.repository;

import com.example.agend_ai.entity.CalendarSyncOutbox;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.repository.query.Param;

public interface CalendarSyncOutboxRepository extends JpaRepository<CalendarSyncOutbox, UUID> {
  boolean existsByItemId(UUID itemId);
  void deleteAllByUser_Id(UUID userId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("""
      select o from CalendarSyncOutbox o
      where o.availableAt <= :now
      order by o.availableAt, o.updatedAt
      """)
  List<CalendarSyncOutbox> findReady(@Param("now") Instant now);
}
