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
}
