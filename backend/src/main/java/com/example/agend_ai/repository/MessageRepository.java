package com.example.agend_ai.repository;

import com.example.agend_ai.entity.Message;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MessageRepository extends JpaRepository<Message, UUID> {
  @Query(
      "select m from Message m where m.session.id = :sessionId and (m.intent is null or m.intent <>"
          + " 'stt_not_recognized') order by m.createdAt desc, m.id desc")
  List<Message> context(UUID sessionId, Pageable pageable);

  java.util.Optional<Message> findBySessionIdAndClientMessageId(
      UUID sessionId, UUID clientMessageId);
}
