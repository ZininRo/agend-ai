package com.example.agend_ai.api.dto;

import java.util.Map;

public record HealthResponse(String status, Map<String, String> components) {
}
