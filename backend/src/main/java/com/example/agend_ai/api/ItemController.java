package com.example.agend_ai.api;

import com.example.agend_ai.api.dto.ItemsResponse;
import com.example.agend_ai.service.ItemQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/items")
@RequiredArgsConstructor
public class ItemController {

    private final ItemQueryService service;

    @GetMapping
    public ItemsResponse list(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) List<String> type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String timezone) {
        return service.list(from, to, type, status, timezone);
    }
}
