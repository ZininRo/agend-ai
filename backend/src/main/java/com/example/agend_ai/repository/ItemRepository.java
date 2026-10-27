package com.example.agend_ai.repository;

import com.example.agend_ai.entity.Item;
import com.example.agend_ai.entity.enums.ItemStatus;
import com.example.agend_ai.entity.enums.ItemType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ItemRepository extends JpaRepository<Item, UUID> {

    /**
     * Записи периода [from, to) по основной дате:
     * start_at для встреч и напоминаний, due_at для задач.
     */
    @Query("""
            select i from Item i
            where i.user.id = :userId
              and i.deletedAt is null
              and i.type in :types
              and i.status in :statuses
              and (
                   (i.type = com.example.agend_ai.entity.enums.ItemType.TASK
                        and i.dueAt >= :from and i.dueAt < :to)
                or (i.type <> com.example.agend_ai.entity.enums.ItemType.TASK
                        and i.startAt >= :from and i.startAt < :to)
              )
            order by coalesce(i.startAt, i.dueAt), i.createdAt
            """)
    List<Item> findInPeriod(@Param("userId") UUID userId,
                            @Param("from") Instant from,
                            @Param("to") Instant to,
                            @Param("types") Collection<ItemType> types,
                            @Param("statuses") Collection<ItemStatus> statuses);

    @Query("""
            select i from Item i
            where i.user.id = :userId
              and i.deletedAt is null
              and i.type = :type
              and i.status = :status
              and i.id <> :excludeId
              and i.startAt < :candidateEnd
              and (
                   (i.endAt is not null and i.endAt > :candidateStart)
                or (i.endAt is null and i.startAt > :defaultWindowStart)
              )
            order by i.startAt, i.createdAt
            """)
    List<Item> findMeetingConflicts(@Param("userId") UUID userId,
                                    @Param("candidateStart") Instant candidateStart,
                                    @Param("candidateEnd") Instant candidateEnd,
                                    @Param("defaultWindowStart") Instant defaultWindowStart,
                                    @Param("excludeId") UUID excludeId,
                                    @Param("status") ItemStatus status,
                                    @Param("type") ItemType type);

    @Query("""
            select i from Item i
            where i.user.id = :userId
              and i.deletedAt is null
              and lower(i.title) like lower(concat('%', :query, '%'))
            order by i.updatedAt desc
            """)
    List<Item> findByTitleQuery(@Param("userId") UUID userId,
                                @Param("query") String query,
                                org.springframework.data.domain.Pageable pageable);
}