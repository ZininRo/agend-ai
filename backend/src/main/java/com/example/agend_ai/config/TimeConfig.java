package com.example.agend_ai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class TimeConfig {

    /** Серверные часы; в тестах подменяются фиксированным Clock. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
