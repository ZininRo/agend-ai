package com.example.agend_ai.api;

import com.example.agend_ai.api.dto.ItemDto;
import com.example.agend_ai.api.error.AppException;
import com.example.agend_ai.api.error.ErrorCode;
import com.example.agend_ai.common.DefaultUser;
import com.example.agend_ai.entity.Item;
import com.example.agend_ai.entity.enums.ItemStatus;
import com.example.agend_ai.repository.ItemRepository;
import com.example.agend_ai.service.ZoneResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.util.UUID;

/** Ручное управление записями MVP-пользователя. */
@RestController
@RequestMapping("/api/items")
@RequiredArgsConstructor
public class ItemManagementController {
    private final ItemRepository repository;
    private final ZoneResolver zones;
    private final JdbcTemplate jdbc;

    public record StatusUpdate(String status) {}

    private Item find(UUID id) {
        return repository.findById(id)
                .filter(i -> i.getUser().getId().equals(DefaultUser.ID) && i.getDeletedAt() == null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Запись не найдена"));
    }

    @PatchMapping("/{id}/status")
    @Transactional
    public ItemDto changeStatus(@PathVariable UUID id, @RequestBody StatusUpdate body,
                                @RequestParam(required = false) String timezone) {
        if (body == null || body.status() == null) throw new AppException(ErrorCode.REQUEST_INVALID);
        final ItemStatus status;
        try {
            status = ItemStatus.valueOf(body.status().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new AppException(ErrorCode.REQUEST_INVALID);
        }
        Item item = find(id);
        ItemStatus previous = item.getStatus();
        item.setStatus(status);
        repository.saveAndFlush(item);
        if (previous != status) resetPush(item.getId());
        return ItemDto.from(item, zones.resolve(timezone));
    }

    public record ItemUpdate(String title, String description, String startAt, String endAt,
                             String dueAt, String place, String status) {}

    @PatchMapping("/{id}")
    @Transactional
    public ItemDto update(@PathVariable UUID id, @RequestBody ItemUpdate body,
                          @RequestParam(required = false) String timezone) {
        if (body == null || body.title() == null || body.title().isBlank() || body.title().length() > 255)
            throw new AppException(ErrorCode.REQUEST_INVALID);
        Item item = find(id);
        ItemStatus previousStatus = item.getStatus();
        Instant previousStart = item.getStartAt();
        item.setTitle(body.title().trim());
        item.setDescription(body.description());
        if (body.place() != null && body.place().length() > 255) throw new AppException(ErrorCode.REQUEST_INVALID);
        item.setPlace(body.place());
        try {
            if (body.status() == null) throw new IllegalArgumentException("Missing status");
            item.setStatus(ItemStatus.valueOf(body.status().trim().toUpperCase(Locale.ROOT)));
            Instant start = parseDate(body.startAt());
            Instant end = parseDate(body.endAt());
            Instant due = parseDate(body.dueAt());
            if (start != null && end != null && end.isBefore(start)) throw new IllegalArgumentException("End before start");
            switch (item.getType()) {
                case TASK -> { if (due == null) throw new IllegalArgumentException("Missing due date"); item.setDueAt(due); }
                case MEETING -> { if (start == null) throw new IllegalArgumentException("Missing start"); item.setStartAt(start); item.setEndAt(end); }
                case REMINDER -> { if (start == null) throw new IllegalArgumentException("Missing start"); item.setStartAt(start); }
            }
        } catch (IllegalArgumentException ex) {
            throw new AppException(ErrorCode.REQUEST_INVALID);
        }
        repository.saveAndFlush(item);
        if (previousStatus != item.getStatus() || !java.util.Objects.equals(previousStart, item.getStartAt()))
            resetPush(item.getId());
        return ItemDto.from(item, zones.resolve(timezone));
    }

    private static Instant parseDate(String text) {
        if (text == null || text.isBlank()) return null;
        return OffsetDateTime.parse(text).toInstant();
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable UUID id) {
        Item item = find(id);
        item.softDelete();
        repository.save(item);
        resetPush(item.getId());
    }

    // Reset prior delivery keys when a user re-arms or reschedules an item.
    // The scheduler will enqueue only when the new scheduled time becomes due.
    private void resetPush(UUID itemId) {
        jdbc.update("DELETE FROM push_deliveries WHERE item_id = ?", itemId);
    }
}
