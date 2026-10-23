package com.example.agend_ai.service;

import com.example.agend_ai.api.dto.ItemDto;
import com.example.agend_ai.api.dto.ItemsResponse;
import com.example.agend_ai.api.error.AppException;
import com.example.agend_ai.api.error.ErrorCode;
import com.example.agend_ai.common.DefaultUser;
import com.example.agend_ai.entity.Item;
import com.example.agend_ai.entity.enums.ItemStatus;
import com.example.agend_ai.entity.enums.ItemType;
import com.example.agend_ai.repository.ItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ItemQueryService {

    static final int MAX_RANGE_DAYS = 62;

    private final ItemRepository itemRepository;
    private final ZoneResolver zones;

    @Transactional(readOnly = true)
    public ItemsResponse list(LocalDate from, LocalDate to, List<String> types, String status, String timezone) {
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) + 1 > MAX_RANGE_DAYS) {
            throw new AppException(ErrorCode.REQUEST_INVALID);
        }
        ZoneId zone = zones.resolve(timezone);
        Set<ItemType> typeSet = parseTypes(types);
        Set<ItemStatus> statusSet = parseStatus(status);

        Instant fromInstant = from.atStartOfDay(zone).toInstant();
        Instant toInstant = to.plusDays(1).atStartOfDay(zone).toInstant();

        List<Item> items = itemRepository.findInPeriod(DefaultUser.ID, fromInstant, toInstant, typeSet, statusSet);
        return new ItemsResponse(from, to, items.stream().map(i -> ItemDto.from(i, zone)).toList());
    }

    private Set<ItemType> parseTypes(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return EnumSet.allOf(ItemType.class);
        }
        Set<ItemType> result = EnumSet.noneOf(ItemType.class);
        for (String s : raw) {
            try {
                result.add(ItemType.valueOf(s.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new AppException(ErrorCode.REQUEST_INVALID);
            }
        }
        return result;
    }

    private Set<ItemStatus> parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return EnumSet.allOf(ItemStatus.class);
        }
        try {
            return EnumSet.of(ItemStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            throw new AppException(ErrorCode.REQUEST_INVALID);
        }
    }
}
