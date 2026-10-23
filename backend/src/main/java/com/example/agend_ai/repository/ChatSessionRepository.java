package com.example.agend_ai.repository;

import com.example.agend_ai.entity.ChatSession;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatSessionRepository extends JpaRepository<ChatSession, UUID> {

  /** Блокировка строки сессии на время обработки /api/message (фаза 4). */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select s from ChatSession s where s.id = :id")
  Optional<ChatSession> findByIdForUpdate(@Param("id") UUID id);

  @Modifying
  @Query(
      value =
          "insert into sessions (id,user_id,created_at,updated_at) values (:id,:userId,now(),now())"
              + " on conflict (id) do nothing",
      nativeQuery = true)
  void ensure(@Param("id") UUID id, @Param("userId") UUID userId);
}
