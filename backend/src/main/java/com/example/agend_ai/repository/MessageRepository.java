package com.example.agend_ai.repository;

import com.example.agend_ai.entity.Message;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MessageRepository extends JpaRepository<Message, UUID> {
  @Query(
      "select m from Message m where m.session.id = :sessionId and m.user.id = :userId"
          + " and (m.intent is null or m.intent <> 'stt_not_recognized')"
          + " order by m.createdAt desc, m.role asc")
  List<Message> context(
      @Param("sessionId") UUID sessionId,
      @Param("userId") UUID userId,
      Pageable pageable);

  @Query(
      "select m from Message m left join fetch m.item"
          + " where m.session.id = :sessionId and m.user.id = :userId"
          + " order by m.createdAt desc, m.role asc, m.id desc")
  List<Message> history(
      @Param("sessionId") UUID sessionId,
      @Param("userId") UUID userId,
      Pageable pageable);

  @Query(
      "select m from Message m where m.session.id = :sessionId and m.user.id = :userId"
          + " and m.clientMessageId = :clientMessageId")
  java.util.Optional<Message> findBySessionIdAndUserIdAndClientMessageId(
      @Param("sessionId") UUID sessionId,
      @Param("userId") UUID userId,
      @Param("clientMessageId") UUID clientMessageId);

  @Query("select m from Message m where m.id = :id and m.user.id = :userId")
  java.util.Optional<Message> findByIdAndUserId(
      @Param("id") UUID id, @Param("userId") UUID userId);
}