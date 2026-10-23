package com.example.agend_ai.service;

import java.time.*;
import java.time.format.DateTimeParseException;
import java.time.temporal.*;
import java.util.*;
import java.util.regex.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** All calendar arithmetic uses server time in the requested zone. No LLM date arithmetic. */
@Component
@RequiredArgsConstructor
public class DateResolver {
  private final Clock clock;
  private static final String[] DAYS = {
    "понедельник", "вторник", "сред", "четверг", "пятниц", "суббот", "воскрес"
  };
  private static final Map<String, Integer> HOURS =
      Map.ofEntries(
          Map.entry("один", 1),
          Map.entry("час", 1),
          Map.entry("два", 2),
          Map.entry("две", 2),
          Map.entry("три", 3),
          Map.entry("четыре", 4),
          Map.entry("пять", 5),
          Map.entry("шесть", 6),
          Map.entry("семь", 7),
          Map.entry("восемь", 8),
          Map.entry("девять", 9),
          Map.entry("десять", 10),
          Map.entry("одиннадцать", 11),
          Map.entry("двенадцать", 12));

  public record ResolvedDateTime(
      Instant startAt,
      Instant dueAt,
      boolean needsTimeClarification,
      boolean needsDateClarification,
      String reason) {}

  public record Period(Instant from, Instant to, LocalDate first, LocalDate last) {}

  private record DatePart(LocalDate value, String reason) {}

  private static String norm(String s) {
    return s == null ? "" : s.strip().toLowerCase(Locale.ROOT).replace('ё', 'е');
  }

  public ResolvedDateTime resolve(
      String dateExpr, String timeExpr, ZoneId zone, Instant nowOverride) {
    ZonedDateTime now = (nowOverride == null ? clock.instant() : nowOverride).atZone(zone);
    String d = norm(dateExpr), t = norm(timeExpr);
    String relative = t.startsWith("через ") ? t : d.startsWith("через ") ? d : "";
    if (!relative.isEmpty()) {
      Matcher m =
          Pattern.compile("через (\\d+|два|две|три|один|час) (часа?|часов|минут[уы]?)")
              .matcher(relative);
      if (!m.matches()) return unresolved("invalid_date");
      int n = HOURS.getOrDefault(m.group(1), 0);
      try {
        if (n == 0) n = Integer.parseInt(m.group(1));
      } catch (NumberFormatException e) {
        return unresolved("invalid_date");
      }
      if (n < 1 || n > 10000) return unresolved("invalid_date");
      Instant at =
          (m.group(2).startsWith("час") ? now.plusHours(n) : now.plusMinutes(n))
              .truncatedTo(ChronoUnit.MINUTES)
              .toInstant();
      return new ResolvedDateTime(at, at, false, false, null);
    }
    // ISO timestamps are accepted literally, including their explicit offset.
    if (d.contains("t")) {
      try {
        Instant at = OffsetDateTime.parse(dateExpr.strip()).toInstant();
        return new ResolvedDateTime(
            at,
            at,
            false,
            at.isBefore(now.toInstant()),
            at.isBefore(now.toInstant()) ? "past" : null);
      } catch (DateTimeException e) {
        return unresolved("invalid_date");
      }
    }
    DatePart part = date(d, now.toLocalDate(), true);
    if (part.reason != null) return unresolved(part.reason);
    LocalTime time = parseTime(t);
    if (!t.isEmpty() && time == null)
      return new ResolvedDateTime(null, null, true, false, "invalid_time");
    LocalDateTime local = part.value.atTime(time == null ? LocalTime.of(23, 59) : time);
    // A nonexistent or repeated local time during DST needs user clarification.
    if (zone.getRules().getValidOffsets(local).size() != 1) return unresolved("ambiguous_time");
    Instant at = local.atZone(zone).toInstant();
    boolean past = at.isBefore(now.toInstant());
    return new ResolvedDateTime(
        time == null ? null : at, at, time == null, past, past ? "past" : null);
  }

  private ResolvedDateTime unresolved(String reason) {
    return new ResolvedDateTime(null, null, false, true, reason);
  }

  private DatePart date(String d, LocalDate today, boolean creating) {
    if (d.isEmpty()) return new DatePart(null, "missing_date");
    if (d.equals("сегодня")) return new DatePart(today, null);
    if (d.equals("завтра")) return new DatePart(today.plusDays(1), null);
    if (d.equals("послезавтра")) return new DatePart(today.plusDays(2), null);
    if (d.equals("вчера")) return new DatePart(today.minusDays(1), null);
    if (d.matches("(до )?кон(ца|ец) недели"))
      return new DatePart(today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)), null);
    if (d.matches(".*\\bнедел[а-я]*.*")) return new DatePart(null, "missing_day");
    try {
      return new DatePart(LocalDate.parse(d), null);
    } catch (DateTimeParseException ignored) {
    }
    for (int i = 0; i < DAYS.length; i++) {
      // Only a weekday expression, never an arbitrary substring of a sentence.
      if (d.matches("(?:(?:в|во|на) )?(?:следующ(?:ий|ую|ее) )?" + DAYS[i] + "[а-я]*")) {
        DayOfWeek target = DayOfWeek.of(i + 1);
        if (creating && today.getDayOfWeek() == target && !d.contains("следующ"))
          return new DatePart(null, "ambiguous_date");
        LocalDate at =
            today.with(
                d.contains("следующ")
                    ? TemporalAdjusters.next(target)
                    : TemporalAdjusters.nextOrSame(target));
        return new DatePart(at, null);
      }
    }
    return new DatePart(null, "invalid_date");
  }

  private LocalTime parseTime(String text) {
    String t = text.replaceFirst("^в\\s+", "");
    Matcher m =
        Pattern.compile("(\\d{1,2})(?:[:.](\\d{2}))?(?: (утра|дня|вечера|ночи))?").matcher(t);
    if (!m.matches()) {
      for (var e : HOURS.entrySet()) {
        if (t.matches(e.getKey() + "(?: (утра|дня|вечера|ночи))?"))
          return parseTime(t.replaceFirst(e.getKey(), e.getValue().toString()));
      }
      return null;
    }
    int h = Integer.parseInt(m.group(1)),
        min = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
    if (h > 23 || min > 59) return null;
    String suffix = m.group(3);
    if (suffix != null) {
      if (h < 1 || h > 12) return null;
      h = h % 12 + ((suffix.equals("дня") || suffix.equals("вечера")) ? 12 : 0);
    } else if (m.group(2) == null && h >= 1 && h <= 7) h += 12;
    return LocalTime.of(h, min);
  }

  public Period period(String expression, ZoneId zone) {
    String s = norm(expression);
    ZonedDateTime now = clock.instant().atZone(zone);
    LocalDate first, last;
    Instant from;
    if (s.matches("(?:на |в )?(?:этой|эту|текущую|текущей) недел[еюи]")) {
      first = now.toLocalDate();
      last = first.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
      from = now.toInstant();
    } else if (s.matches("(?:на )?следующ(?:ей|ую) недел[еюи]")) {
      first = now.toLocalDate().with(TemporalAdjusters.next(DayOfWeek.MONDAY));
      last = first.plusDays(6);
      from = first.atStartOfDay(zone).toInstant();
    } else {
      Matcher range =
          Pattern.compile(
                  "(?:с )?(\\d{4}-\\d{2}-\\d{2})(?:\\s+(?:по|до)\\s+|/)(\\d{4}-\\d{2}-\\d{2})")
              .matcher(s);
      if (range.matches()) {
        try {
          first = LocalDate.parse(range.group(1));
          last = LocalDate.parse(range.group(2));
        } catch (DateTimeException e) {
          throw new IllegalArgumentException("period");
        }
      } else {
        DatePart part = date(s, now.toLocalDate(), false);
        if (part.reason != null) throw new IllegalArgumentException("period");
        first = part.value;
        last = first;
      }
      from = first.atStartOfDay(zone).toInstant();
    }
    if (last.isBefore(first) || ChronoUnit.DAYS.between(first, last) >= 62)
      throw new IllegalArgumentException("period");
    return new Period(from, last.plusDays(1).atStartOfDay(zone).toInstant(), first, last);
  }
}
