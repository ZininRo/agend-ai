package com.example.agend_ai.service;

import com.example.agend_ai.config.AppProperties;
import com.example.agend_ai.entity.ChatSession;
import com.example.agend_ai.entity.Item;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Управляет только состоянием slot-filling/уточнений, не выполняя бизнес-действия. */
@Service
@RequiredArgsConstructor
public class ClarificationService {
  private final ObjectMapper mapper;
  private final Clock clock;
  private final AppProperties app;

  public boolean expired(JsonNode pending) {
    try {
      Instant created = Instant.parse(pending.path("created_at").asText());
      return clock.instant().minusSeconds(app.pending().ttlMin() * 60L).isAfter(created);
    } catch (RuntimeException e) {
      return true;
    }
  }

  public void clearExpired(ChatSession session) {
    if (session.getPendingClarification() != null && expired(session.getPendingClarification())) {
      session.setPendingClarification(null);
    }
  }

  public void missingSlot(ChatSession session, String intent, JsonNode draft, String awaiting) {
    ObjectNode node = mapper.createObjectNode();
    node.put("kind", "missing_slot");
    node.put("intent", intent);
    node.set("draft", draft.deepCopy());
    node.set("awaiting", mapper.valueToTree(List.of(awaiting)));
    node.put("created_at", clock.instant().toString());
    session.setPendingClarification(node);
  }

  public void fromPayload(
      ChatSession session, JsonNode payload, String kind, String awaiting) {
    Map<String, Object> pending = new LinkedHashMap<>();
    pending.put("kind", kind);
    pending.put("intent", payload.path("intent").asText());
    pending.put("draft", payload.path("slots"));
    pending.put("awaiting", List.of(awaiting));
    pending.put("created_at", clock.instant().toString());
    session.setPendingClarification(mapper.valueToTree(pending));
  }

  public void conflict(ChatSession session, JsonNode payload, Item conflict) {
    Map<String, Object> pending = new LinkedHashMap<>();
    pending.put("kind", "conflict");
    pending.put("intent", payload.path("intent").asText());
    pending.put("draft", payload.path("slots"));
    pending.put("awaiting", List.of("decision"));
    pending.put("conflict_item_id", conflict.getId());
    pending.put("created_at", clock.instant().toString());
    session.setPendingClarification(mapper.valueToTree(pending));
  }

  public void chooseItem(ChatSession session, JsonNode payload, List<Item> candidates) {
    Map<String, Object> pending = new LinkedHashMap<>();
    pending.put("kind", "choose_item");
    pending.put("intent", payload.path("intent").asText());
    pending.put("draft", payload.path("slots"));
    pending.put("awaiting", List.of("item"));
    pending.put("candidate_ids", candidates.stream().map(Item::getId).toList());
    pending.put("created_at", clock.instant().toString());
    session.setPendingClarification(mapper.valueToTree(pending));
  }

  public void confirmDelete(ChatSession session, JsonNode payload, Item item) {
    Map<String, Object> pending = new LinkedHashMap<>();
    pending.put("kind", "confirm_delete");
    pending.put("intent", "delete_item");
    pending.put("draft", payload.path("slots"));
    pending.put("awaiting", List.of("decision"));
    pending.put("item_id", item.getId().toString());
    pending.put("created_at", clock.instant().toString());
    session.setPendingClarification(mapper.valueToTree(pending));
  }

  public JsonNode resumePayload(JsonNode pending, String intent, String slotName, String value) {
    ObjectNode resumed = mapper.createObjectNode();
    resumed.put("intent", intent);
    resumed.put("confidence", 1.0);
    ObjectNode slots =
        pending.path("draft").isObject()
            ? (ObjectNode) pending.path("draft").deepCopy()
            : mapper.createObjectNode();
    if (slotName != null && value != null) slots.put(slotName, value);
    resumed.set("slots", slots);
    return resumed;
  }

  public List<String> quickReplies(ChatSession session, String result) {
    if (!"clarification".equals(result) || session.getPendingClarification() == null) {
      return List.of();
    }
    return "conflict".equals(session.getPendingClarification().path("kind").asText())
        ? List.of("Выбрать другое время", "Поставить в это же")
        : List.of();
  }
}
