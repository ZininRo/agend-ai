package com.example.agend_ai.api;

import com.example.agend_ai.api.dto.HealthResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class HealthController {

    private final JdbcTemplate jdbc;

    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        String db = "UP";
        try {
            jdbc.queryForObject("select 1", Integer.class);
        } catch (Exception e) {
            log.warn("Health: db is down", e);
            db = "DOWN";
        }
        // stt/llm: UNKNOWN, пока нет реальных обращений (подключим в фазах 2–3)
        Map<String, String> components = new LinkedHashMap<>();
        components.put("db", db);
        components.put("stt", "UNKNOWN");
        components.put("llm", "UNKNOWN");

        boolean up = "UP".equals(db);
        return ResponseEntity.status(up ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(new HealthResponse(up ? "UP" : "DOWN", components));
    }
}
