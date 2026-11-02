package com.example.agend_ai.service.nlu;

import com.example.agend_ai.config.AppProperties;
import com.example.agend_ai.entity.ChatSession;
import com.example.agend_ai.repository.MessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityNotFoundException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/** Builds the pure NLU context from persistent chat state. */
@Component
@RequiredArgsConstructor
public class NluContextFactory {
  private final AppProperties app;
  private final MessageRepository messages;
  private final ObjectMapper mapper;

  public NluContext from(ChatSession session, String text, ZoneId zone, Instant now) {
    var recent =
        new ArrayList<>(
            messages.context(
                session.getId(),
                session.getUser().getId(),
                PageRequest.of(0, Math.max(1, app.context().maxMessages()))));
    Collections.reverse(recent);
    List<NluContext.HistoryEntry> history =
        recent.stream()
            .map(m -> new NluContext.HistoryEntry(m.getRole().name(), m.getContent()))
            .toList();

    ObjectNode lastItem = null;
    if (session.getLastItem() != null) {
      try {
        var item = session.getLastItem();
        lastItem = mapper.createObjectNode();
        lastItem.put("id", item.getId().toString());
        lastItem.put("type", String.valueOf(item.getType()));
        lastItem.put("title", item.getTitle());
        lastItem.put("status", String.valueOf(item.getStatus()));
        lastItem.put(
            "time", String.valueOf(item.getStartAt() != null ? item.getStartAt() : item.getDueAt()));
      } catch (EntityNotFoundException missingItem) {
        // A referenced item may have been deleted after the session was created.
        session.setLastItem(null);
      }
    }

    var pending = session.getPendingClarification();
    if (pending != null && expired(pending.path("created_at").asText(), now)) pending = null;
    return new NluContext(now, zone, text, history, lastItem, pending);
  }

  private boolean expired(String createdAt, Instant now) {
    try {
      Instant created = Instant.parse(createdAt);
      return now.minusSeconds(app.pending().ttlMin() * 60L).isAfter(created);
    } catch (DateTimeParseException | NullPointerException e) {
      return true;
    }
  }
}
