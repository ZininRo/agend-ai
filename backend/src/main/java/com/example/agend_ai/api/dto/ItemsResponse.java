package com.example.agend_ai.api.dto;

import java.time.LocalDate;
import java.util.List;

public record ItemsResponse(LocalDate from, LocalDate to, List<ItemDto> items) {
}
