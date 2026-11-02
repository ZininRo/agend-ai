package com.example.agend_ai.service.nlu;

import com.example.agend_ai.entity.ChatSession;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Production facade that adds persistent chat context around the pure NLU engine. */
@Service
@RequiredArgsConstructor
public class NluService {
  private final NluContextFactory contexts;
  private final NluEngine engine;

  public Optional<JsonNode> understand(ChatSession session, String text, ZoneId zone, Instant now) {
    return engine.understand(contexts.from(session, text, zone, now)).payloadOptional();
  }
}
