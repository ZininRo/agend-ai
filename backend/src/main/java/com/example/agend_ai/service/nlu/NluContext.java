package com.example.agend_ai.service.nlu;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/** Immutable, reproducible input passed to the NLU layer. */
public record NluContext(
    Instant now,
    ZoneId zone,
    String text,
    List<HistoryEntry> history,
    JsonNode lastItem,
    JsonNode pending) {

  public NluContext {
    if (now == null) throw new IllegalArgumentException("now is required");
    if (zone == null) throw new IllegalArgumentException("zone is required");
    if (text == null) throw new IllegalArgumentException("text is required");
    history = history == null ? List.of() : List.copyOf(history);
    lastItem = lastItem == null ? null : lastItem.deepCopy();
    pending = pending == null ? null : pending.deepCopy();
  }

  public record HistoryEntry(String role, String text) {
    public HistoryEntry {
      if (role == null || role.isBlank()) throw new IllegalArgumentException("history role is required");
      if (text == null) throw new IllegalArgumentException("history text is required");
    }
  }
}
