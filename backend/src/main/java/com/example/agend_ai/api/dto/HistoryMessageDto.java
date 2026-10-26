package com.example.agend_ai.api.dto;

import com.example.agend_ai.entity.Message;
import java.time.ZoneId;
import java.util.Locale;
import java.util.UUID;

public record HistoryMessageDto(UUID id, String role, String text, String createdAt, ItemDto item) {
  public static HistoryMessageDto from(Message m, ZoneId zone) {
    return new HistoryMessageDto(
        m.getId(), m.getRole().name().toLowerCase(Locale.ROOT), m.getContent(),
        ItemDto.format(m.getCreatedAt(), zone),
        m.getItem() == null ? null : ItemDto.from(m.getItem(), zone));
  }
}
