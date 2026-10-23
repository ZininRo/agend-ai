package com.example.agend_ai.service;

import com.example.agend_ai.config.AppProperties;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.ZoneId;

@Component
public class ZoneResolver {

    private final ZoneId defaultZone;

    public ZoneResolver(AppProperties props) {
        this.defaultZone = ZoneId.of(props.timezone());
    }

    /** Невалидный или пустой пояс → app.timezone. */
    public ZoneId resolve(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return defaultZone;
        }
        try {
            return ZoneId.of(timezone.trim());
        } catch (DateTimeException e) {
            return defaultZone;
        }
    }
}
