package com.example.agend_ai.service;

import com.example.agend_ai.entity.ChatSession;
import com.example.agend_ai.entity.Item;
import com.example.agend_ai.entity.enums.ItemType;
import com.example.agend_ai.repository.ItemRepository;
import com.example.agend_ai.security.CurrentUser;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Разрешает ссылки пользователя на существующую запись: last, id из уточнения или поиск. */
@Service
@RequiredArgsConstructor
public class TargetResolver {
  private final ItemRepository items;
  private final ItemSearchService search;
  private final CurrentUser currentUser;

  public record Resolution(Item item, List<Item> candidates, boolean byQuery) {}

  public Resolution resolve(ChatSession session, JsonNode slots) {
    UUID userId = currentUser.id();
    String selected = slot(slots, "selected_item_id");
    if (selected != null) {
      try {
        Item item = items.findActiveByIdAndUserId(UUID.fromString(selected), userId).orElse(null);
        if (item != null) return new Resolution(item, List.of(), true);
      } catch (IllegalArgumentException ignored) {
        // malformed id is handled as an unresolved target
      }
      return new Resolution(null, List.of(), true);
    }

    String ref = slot(slots, "item_ref");
    if ("last".equals(ref)) {
      Item item = session.getLastItem();
      if (item == null
          || item.getDeletedAt() != null
          || item.getUser() == null
          || !userId.equals(item.getUser().getId())) {
        return new Resolution(null, List.of(), false);
      }
      String requestedType = slot(slots, "item_type");
      if (requestedType != null
          && item.getType() != ItemType.valueOf(requestedType.toUpperCase(Locale.ROOT))) {
        return new Resolution(null, List.of(), false);
      }
      return new Resolution(item, List.of(), false);
    }

    if ("by_query".equals(ref)) {
      String query = slot(slots, "query");
      if (query == null) return new Resolution(null, List.of(), true);
      var ranked = search.search(userId, query, 3);
      String requestedType = slot(slots, "item_type");
      if (requestedType != null) {
        ItemType expected = ItemType.valueOf(requestedType.toUpperCase(Locale.ROOT));
        ranked = ranked.stream().filter(c -> c.item().getType() == expected).toList();
      }
      var confident = search.confident(ranked);
      if (confident.isPresent()) return new Resolution(confident.get(), List.of(), true);
      return new Resolution(
          null, ranked.stream().map(ItemSearchService.Candidate::item).toList(), true);
    }
    return new Resolution(null, List.of(), false);
  }

  private static String slot(JsonNode slots, String name) {
    JsonNode n = slots.get(name);
    return n == null || n.isNull() ? null : n.asText().strip();
  }
}
