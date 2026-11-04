package com.example.agend_ai.repository;

import com.example.agend_ai.entity.ExternalCalendar;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExternalCalendarRepository extends JpaRepository<ExternalCalendar, UUID> {
  List<ExternalCalendar> findAllByConnection_Id(UUID connectionId);
  List<ExternalCalendar> findAllByConnection_IdAndSyncEnabledTrueAndAvailableTrue(UUID connectionId);
  Optional<ExternalCalendar> findByConnection_IdAndProviderCalendarIdHash(UUID connectionId, String hash);
  Optional<ExternalCalendar> findByIdAndConnection_User_Id(UUID id, UUID userId);
  Optional<ExternalCalendar> findByConnection_IdAndWriteTargetTrueAndAvailableTrue(UUID connectionId);
  Optional<ExternalCalendar> findByWatchChannelId(String watchChannelId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from ExternalCalendar c where c.id = :id")
  Optional<ExternalCalendar> findByIdForUpdate(@Param("id") UUID id);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update ExternalCalendar c set c.available = false where c.connection.id = :connectionId")
  int markAllUnavailable(@Param("connectionId") UUID connectionId);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update ExternalCalendar c set c.writeTarget = false where c.connection.id = :connectionId")
  int clearWriteTarget(@Param("connectionId") UUID connectionId);

  @Query("""
      select c from ExternalCalendar c
      where c.syncEnabled = true and c.available = true
        and c.connection.syncEnabled = true and c.connection.status = 'CONNECTED'
        and (c.syncRequestedAt is not null or c.lastSyncAt is null or c.lastSyncAt < :before)
      order by coalesce(c.syncRequestedAt, c.lastSyncAt, c.createdAt)
      """)
  List<ExternalCalendar> findCalendarsDueForSync(@Param("before") Instant before);
}
