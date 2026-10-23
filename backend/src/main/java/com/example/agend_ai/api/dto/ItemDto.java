package com.example.agend_ai.api.dto;

import com.example.agend_ai.entity.Item;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;

/** Схема Item из разд. 6.7. Даты — ISO-8601 со смещением пояса пользователя. */
public record ItemDto(
        UUID id,
        String type,
        String title,
        String description,
        String startAt,
        String endAt,
        String dueAt,
        String place,
        String status,
        String createdAt,
        String updatedAt) {

    public static ItemDto from(Item i, ZoneId zone) {
        return new ItemDto(
                i.getId(),
                i.getType().name().toLowerCase(Locale.ROOT),
                i.getTitle(),
                i.getDescription(),
                format(i.getStartAt(), zone),
                format(i.getEndAt(), zone),
                format(i.getDueAt(), zone),
                i.getPlace(),
                i.getStatus().name().toLowerCase(Locale.ROOT),
                format(i.getCreatedAt(), zone),
                format(i.getUpdatedAt(), zone));
    }

    public static String format(Instant t, ZoneId zone) {
        if (t == null) {
            return null;
        }
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME
                .format(t.truncatedTo(ChronoUnit.SECONDS).atZone(zone).toOffsetDateTime());
    }
}
