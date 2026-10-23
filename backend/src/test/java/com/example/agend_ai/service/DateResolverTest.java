package com.example.agend_ai.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.*;

import static org.assertj.core.api.Assertions.assertThat;

class DateResolverTest {

    private DateResolver resolver;
    private final ZoneId mskZone = ZoneId.of("Europe/Moscow");
    // Фиксированное время из ТЗ: пятница, 23 октября 2026 года, 14:02 MSK
    private final Instant fixedNow = ZonedDateTime.of(2026, 10, 23, 14, 2, 0, 0, mskZone).toInstant();
    private final Clock clock = Clock.fixed(fixedNow, ZoneId.of("UTC"));

    @BeforeEach
    void setUp() {
        resolver = new DateResolver(clock);
    }

    @Test
    void testToday() {
        var res = resolver.resolve("сегодня", "в 15:00", mskZone, fixedNow);
        assertThat(res.startAt()).isNotNull();
        ZonedDateTime zdt = ZonedDateTime.ofInstant(res.startAt(), mskZone);
        assertThat(zdt.getDayOfMonth()).isEqualTo(23);
        assertThat(zdt.getHour()).isEqualTo(15);
    }

    @Test
    void testTomorrow() {
        var res = resolver.resolve("завтра", "в 10:00", mskZone, fixedNow);
        ZonedDateTime zdt = ZonedDateTime.ofInstant(res.startAt(), mskZone);
        assertThat(zdt.getDayOfMonth()).isEqualTo(24);
        assertThat(zdt.getHour()).isEqualTo(10);
    }

    @Test
    void testAfterTomorrow() {
        var res = resolver.resolve("послезавтра", "в 11:00", mskZone, fixedNow);
        ZonedDateTime zdt = ZonedDateTime.ofInstant(res.startAt(), mskZone);
        assertThat(zdt.getDayOfMonth()).isEqualTo(25);
    }

    @Test
    void testInTwoHours() {
        var res = resolver.resolve(null, "через два часа", mskZone, fixedNow);
        ZonedDateTime zdt = ZonedDateTime.ofInstant(res.startAt(), mskZone);
        assertThat(zdt.getHour()).isEqualTo(16);
        assertThat(zdt.getMinute()).isEqualTo(2);
    }

    @Test
    void testBusinessHoursThreeToBecomes15() {
        var res = resolver.resolve("завтра", "в три", mskZone, fixedNow);
        ZonedDateTime zdt = ZonedDateTime.ofInstant(res.startAt(), mskZone);
        assertThat(zdt.getHour()).isEqualTo(15);
    }

    @Test
    void testEndOfWeek() {
        var res = resolver.resolve("до конца недели", null, mskZone, fixedNow);
        assertThat(res.dueAt()).isNotNull();
        ZonedDateTime zdt = ZonedDateTime.ofInstant(res.dueAt(), mskZone);
        assertThat(zdt.getHour()).isEqualTo(23);
        assertThat(zdt.getMinute()).isEqualTo(59);
    }
}