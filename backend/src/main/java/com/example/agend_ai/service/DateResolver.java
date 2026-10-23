package com.example.agend_ai.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class DateResolver {

    private final Clock clock;

    public record ResolvedDateTime(Instant startAt, Instant dueAt, boolean needsTimeClarification, boolean needsDateClarification) {}

    public ResolvedDateTime resolve(String dateExpr, String timeExpr, ZoneId zone, Instant nowOverride) {
        Instant currentInstant = nowOverride != null ? nowOverride : clock.instant();
        ZonedDateTime now = ZonedDateTime.ofInstant(currentInstant, zone);
        LocalDate date = now.toLocalDate();
        LocalTime time = null;
        Instant dueAt = null;
        boolean isDeadlineOnly = false;

        // 1. Разбор даты
        if (dateExpr != null) {
            String d = dateExpr.toLowerCase(Locale.ROOT).trim();
            if (d.contains("послезавтра")) {
                date = date.plusDays(2);
            } else if (d.contains("сегодня")) {
                // оставляем текущую дату
            } else if (d.contains("завтра")) {
                date = date.plusDays(1);
            } else if (d.contains("пятниц")) {
                date = nextDayOfWeek(date, DayOfWeek.FRIDAY);
            } else if (d.contains("понедельник")) {
                date = nextDayOfWeek(date, DayOfWeek.MONDAY);
            } else if (d.contains("вторник")) {
                date = nextDayOfWeek(date, DayOfWeek.TUESDAY);
            } else if (d.contains("сред")) {
                date = nextDayOfWeek(date, DayOfWeek.WEDNESDAY);
            } else if (d.contains("четверг")) {
                date = nextDayOfWeek(date, DayOfWeek.THURSDAY);
            } else if (d.contains("суббот")) {
                date = nextDayOfWeek(date, DayOfWeek.SATURDAY);
            } else if (d.contains("воскрес")) {
                date = nextDayOfWeek(date, DayOfWeek.SUNDAY);
            } else if (d.contains("конца недели") || d.contains("до конца недели")) {
                date = date.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
                dueAt = ZonedDateTime.of(date, LocalTime.of(23, 59), zone).toInstant();
                isDeadlineOnly = true;
            }
        }

        boolean needsDateClarification = false;
        if (date.isBefore(now.toLocalDate())) {
            needsDateClarification = true;
        }

        // 2. Разбор времени
        boolean needsTimeClarification = false;
        if (!isDeadlineOnly && timeExpr != null && !timeExpr.isBlank()) {
            String t = timeExpr.toLowerCase(Locale.ROOT).trim();
            if (t.contains("через два часа") || t.contains("через 2 часа")) {
                ZonedDateTime future = now.plusHours(2).truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
                date = future.toLocalDate();
                time = future.toLocalTime();
            } else {
                Matcher m = Pattern.compile("(\\d{1,2})([:.](\\d{2}))?").matcher(t);
                if (m.find()) {
                    int hour = Integer.parseInt(m.group(1));
                    int minute = m.group(3) != null ? Integer.parseInt(m.group(3)) : 0;

                    if (!t.contains("утра") && !t.contains("вечера") && !t.contains("дня") && !t.contains("ночи")) {
                        if (hour >= 1 && hour <= 7) {
                            hour += 12;
                        }
                    } else if (t.contains("вечера") && hour < 12) {
                        hour += 12;
                    }
                    time = LocalTime.of(hour, minute);
                } else {
                    // Поддержка времени прописью (например, "в три")
                    int hour = parseWordHour(t);
                    if (hour != -1) {
                        if (hour >= 1 && hour <= 7) {
                            hour += 12;
                        }
                        time = LocalTime.of(hour, 0);
                    }
                }
            }
        }

        Instant startAt = null;

        if (isDeadlineOnly) {
            // dueAt уже заполнен для дедлайнов
        } else if (time != null) {
            startAt = ZonedDateTime.of(date, time, zone).toInstant();
        } else {
            dueAt = ZonedDateTime.of(date, LocalTime.of(23, 59), zone).toInstant();
            needsTimeClarification = true;
        }

        return new ResolvedDateTime(startAt, dueAt, needsTimeClarification, needsDateClarification);
    }

    private int parseWordHour(String t) {
        if (t.contains("час") || t.contains("один")) return 1;
        if (t.contains("два")) return 2;
        if (t.contains("три")) return 3;
        if (t.contains("четыре")) return 4;
        if (t.contains("пят")) return 5;
        if (t.contains("шест")) return 6;
        if (t.contains("сем")) return 7;
        if (t.contains("восем")) return 8;
        if (t.contains("девят")) return 9;
        if (t.contains("десят")) return 10;
        if (t.contains("одиннадцат")) return 11;
        if (t.contains("двенадцат")) return 12;
        return -1;
    }

    private LocalDate nextDayOfWeek(LocalDate current, DayOfWeek target) {
        LocalDate next = current.with(TemporalAdjusters.nextOrSame(target));
        if (next.equals(current) && current.getDayOfWeek() == target) {
            return current.with(TemporalAdjusters.next(target));
        }
        return next;
    }
}