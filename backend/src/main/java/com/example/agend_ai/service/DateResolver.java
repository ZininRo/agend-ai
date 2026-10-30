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

  /** Числительные для часов, минут и «через N». Составные (21–59) собираются в {@link #number}. */
  private static final Map<String, Integer> NUMBERS =
      Map.ofEntries(
          Map.entry("ноль", 0),
          Map.entry("один", 1),
          Map.entry("одна", 1),
          Map.entry("одну", 1),
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
          Map.entry("двенадцать", 12),
          Map.entry("тринадцать", 13),
          Map.entry("четырнадцать", 14),
          Map.entry("пятнадцать", 15),
          Map.entry("шестнадцать", 16),
          Map.entry("семнадцать", 17),
          Map.entry("восемнадцать", 18),
          Map.entry("девятнадцать", 19),
          Map.entry("двадцать", 20),
          Map.entry("тридцать", 30),
          Map.entry("сорок", 40),
          Map.entry("пятьдесят", 50));

  private static final String[] MONTH_STEMS = {
    "январ", "феврал", "март", "апрел", "ма", "июн", "июл", "август", "сентябр", "октябр", "ноябр",
    "декабр"
  };

  private static final Pattern PREPOSITION = Pattern.compile("^(?:на|в|во|до|к|ко)\\s+");
  // Не используем \b: с JDK 19 он перестал понимать кириллицу.
  private static final Pattern WEEK_WORD = Pattern.compile("(?:^|.*[^а-я])недел[а-я]*(?:[^а-я].*)?");
  private static final Pattern END_OF_WEEK = Pattern.compile("кон(?:ца|цу|ец)(?: этой)? недели");
  private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
  private static final Pattern DOT_DATE =
      Pattern.compile("(\\d{1,2})\\.(\\d{1,2})(?:\\.(\\d{2}|\\d{4}))?");
  private static final Pattern DAY_MONTH =
      Pattern.compile("(\\d{1,2})(?:-?го)? ([а-я]+)(?: (\\d{4}))?(?: года)?");
  private static final Pattern RELATIVE_DAYS =
      Pattern.compile("через (?:(\\d{1,4}|[а-я ]+?) )?(день|дня|дней|неделю|недели|недель|месяц)");
  private static final Pattern TIME_DIGITS =
      Pattern.compile("(\\d{1,2})(?:[:.](\\d{2}))?(?: час(?:а|ов)?)?(?: (утра|дня|вечера|ночи))?");

  public record ResolvedDateTime(
      Instant startAt,
      Instant dueAt,
      boolean needsTimeClarification,
      boolean needsDateClarification,
      String reason) {}

  public record Period(Instant from, Instant to, LocalDate first, LocalDate last) {}

  private record DatePart(LocalDate value, String reason) {}

  private static String norm(String s) {
    if (s == null) return "";
    return s.strip()
        .toLowerCase(Locale.ROOT)
        .replace('ё', 'е')
        .replaceAll("\\s+", " ")
        .replaceAll("[.,!?;]+$", "");
  }

  private static String withoutPreposition(String d) {
    return PREPOSITION.matcher(d).replaceFirst("");
  }

  public ResolvedDateTime resolve(
      String dateExpr, String timeExpr, ZoneId zone, Instant nowOverride) {
    ZonedDateTime now = (nowOverride == null ? clock.instant() : nowOverride).atZone(zone);
    String d = norm(dateExpr), t = norm(timeExpr);

    // «через два часа», «через час», «через полчаса», «через 2 часа 15 минут»
    String relative = t.startsWith("через ") ? t : d.startsWith("через ") ? d : "";
    if (!relative.isEmpty()) {
      Integer minutes = relativeMinutes(relative);
      if (minutes != null) {
        if (minutes < 1 || minutes > 10000 * 60) return unresolved("invalid_date");
        Instant at = now.plusMinutes(minutes).truncatedTo(ChronoUnit.MINUTES).toInstant();
        return new ResolvedDateTime(at, at, false, false, null);
      }
      // «через три дня», «через неделю» — это дата, разбираем её ниже вместе со временем
      if (!RELATIVE_DAYS.matcher(relative).matches()) return unresolved("invalid_date");
    }

    // ISO-метки времени принимаем буквально: с явным смещением или в часовом поясе пользователя.
    if (d.matches("\\d{4}-\\d{2}-\\d{2}t.*")) {
      Instant at;
      try {
        at = OffsetDateTime.parse(dateExpr.strip()).toInstant();
      } catch (DateTimeException e) {
        try {
          LocalDateTime local = LocalDateTime.parse(dateExpr.strip());
          if (zone.getRules().getValidOffsets(local).size() != 1)
            return unresolved("ambiguous_time");
          at = local.atZone(zone).toInstant();
        } catch (DateTimeException e2) {
          return unresolved("invalid_date");
        }
      }
      boolean past = at.isBefore(now.toInstant());
      return new ResolvedDateTime(at, at, false, past, past ? "past" : null);
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

  /** Минуты из выражения «через …» или null, если это не часы/минуты. */
  private Integer relativeMinutes(String expr) {
    List<String> tokens = new ArrayList<>(Arrays.asList(expr.split(" ")));
    tokens.remove(0); // «через»
    if (tokens.isEmpty()) return null;
    long total = 0;
    int i = 0;
    while (i < tokens.size()) {
      String tok = tokens.get(i);
      if (tok.equals("полчаса")) {
        total += 30;
        i++;
      } else if (tok.equals("час") || tok.equals("минуту")) {
        total += tok.equals("час") ? 60 : 1;
        i++;
      } else if (tok.equals("полтора") && i + 1 < tokens.size() && tokens.get(i + 1).startsWith("час")) {
        total += 90;
        i += 2;
      } else {
        Integer n = null;
        int used = 0;
        for (int len = Math.min(2, tokens.size() - i - 1); len >= 1 && n == null; len--) {
          n = number(tokens.subList(i, i + len));
          used = len;
        }
        if (n == null || i + used >= tokens.size()) return null;
        String unit = tokens.get(i + used);
        if (unit.equals("ч") || unit.startsWith("час")) total += n * 60L;
        else if (unit.equals("мин") || unit.startsWith("минут")) total += n;
        else return null;
        i += used + 1;
      }
      if (total > 10000L * 60) return null;
    }
    return (int) total;
  }

  /** «5», «пять», «двадцать пять» → число; иначе null. */
  private static Integer number(List<String> tokens) {
    if (tokens.size() == 1) {
      String s = tokens.get(0);
      if (s.matches("\\d{1,5}")) return Integer.parseInt(s);
      return NUMBERS.get(s);
    }
    if (tokens.size() == 2) {
      Integer tens = NUMBERS.get(tokens.get(0)), units = NUMBERS.get(tokens.get(1));
      if (tens != null && units != null && tens >= 20 && tens % 10 == 0 && units >= 1 && units <= 9)
        return tens + units;
    }
    return null;
  }

  private DatePart date(String raw, LocalDate today, boolean creating) {
    String d = withoutPreposition(raw);
    if (d.isEmpty()) return new DatePart(null, "missing_date");
    if (d.equals("сегодня")) return new DatePart(today, null);
    if (d.equals("завтра")) return new DatePart(today.plusDays(1), null);
    if (d.equals("послезавтра")) return new DatePart(today.plusDays(2), null);
    if (d.equals("вчера")) return new DatePart(today.minusDays(1), null);
    if (d.equals("позавчера")) return new DatePart(today.minusDays(2), null);
    if (END_OF_WEEK.matcher(d).matches())
      return new DatePart(today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)), null);

    Matcher days = RELATIVE_DAYS.matcher(d);
    if (days.matches()) {
      Integer n = days.group(1) == null ? Integer.valueOf(1) : number(List.of(days.group(1).split(" ")));
      if (n == null || n < 1 || n > 3650) return new DatePart(null, "invalid_date");
      String unit = days.group(2);
      LocalDate at =
          unit.startsWith("дн") || unit.equals("день")
              ? today.plusDays(n)
              : unit.startsWith("недел") ? today.plusWeeks(n) : today.plusMonths(n);
      return new DatePart(at, null);
    }

    if (WEEK_WORD.matcher(d).matches()) {
      boolean hasWeekday = false;
      for (String day : DAYS) hasWeekday |= d.matches(".*" + day + ".*");
      if (!hasWeekday) return new DatePart(null, "missing_day");
    }

    if (ISO_DATE.matcher(d).matches()) {
      try {
        return new DatePart(LocalDate.parse(d), null);
      } catch (DateTimeParseException e) {
        return new DatePart(null, "invalid_date");
      }
    }

    Matcher dot = DOT_DATE.matcher(d);
    if (dot.matches())
      return calendarDate(
          Integer.parseInt(dot.group(1)), Integer.parseInt(dot.group(2)), dot.group(3), today);

    Matcher dm = DAY_MONTH.matcher(d);
    if (dm.matches()) {
      int month = monthOf(dm.group(2));
      if (month > 0) return calendarDate(Integer.parseInt(dm.group(1)), month, dm.group(3), today);
    }

    for (int i = 0; i < DAYS.length; i++) {
      // Only a weekday expression, never an arbitrary substring of a sentence.
      Matcher w =
          Pattern.compile("(?:(этот|эту|эта) |(следующ[а-я]*) )?" + DAYS[i] + "[а-я]*").matcher(d);
      if (w.matches()) {
        DayOfWeek target = DayOfWeek.of(i + 1);
        boolean next = w.group(2) != null, thisWeek = w.group(1) != null;
        if (creating && today.getDayOfWeek() == target && !next && !thisWeek)
          return new DatePart(null, "ambiguous_date");
        LocalDate at =
            today.with(
                next ? TemporalAdjusters.next(target) : TemporalAdjusters.nextOrSame(target));
        return new DatePart(at, null);
      }
    }
    return new DatePart(null, "invalid_date");
  }

  /** «30 октября», «30.10», «30.10.2026». Без года: далёкое прошлое означает следующий год. */
  private DatePart calendarDate(int day, int month, String yearText, LocalDate today) {
    try {
      if (yearText != null) {
        int year = Integer.parseInt(yearText);
        return new DatePart(LocalDate.of(year < 100 ? 2000 + year : year, month, day), null);
      }
      LocalDate at = LocalDate.of(today.getYear(), month, day);
      if (at.isBefore(today) && ChronoUnit.DAYS.between(at, today) > 60) at = at.plusYears(1);
      return new DatePart(at, null);
    } catch (DateTimeException e) {
      return new DatePart(null, "invalid_date");
    }
  }

  private static int monthOf(String word) {
    if (word.equals("мая") || word.equals("май")) return 5;
    for (int i = 0; i < MONTH_STEMS.length; i++)
      if (i != 4 && word.startsWith(MONTH_STEMS[i])) return i + 1;
    return 0;
  }

  private LocalTime parseTime(String text) {
    String t = text.replaceFirst("^(?:в|во|на|к|около)\\s+", "");
    Matcher m = TIME_DIGITS.matcher(t);
    if (!m.matches()) {
      String digits = wordsToDigits(t);
      if (digits == null) return null;
      m = TIME_DIGITS.matcher(digits);
      if (!m.matches()) return null;
    }
    int h = Integer.parseInt(m.group(1)),
        min = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
    if (h > 23 || min > 59) return null;
    String suffix = m.group(3);
    boolean leadingZero = m.group(1).length() == 2 && m.group(1).startsWith("0");
    if (suffix != null) {
      if (h < 1 || h > 12) return null;
      h = h % 12 + ((suffix.equals("дня") || suffix.equals("вечера")) ? 12 : 0);
    } else if (!leadingZero && h >= 1 && h <= 7) {
      h += 12; // «в три», «в 3:30» без утра/вечера — дневное время; «03:30» — буквально
    }
    return LocalTime.of(h, min);
  }

  /** «в три часа дня», «десять сорок пять утра» → «3 дня», «10:45 утра». */
  private static String wordsToDigits(String t) {
    List<String> tokens = new ArrayList<>(Arrays.asList(t.split(" ")));
    String suffix = null;
    if (!tokens.isEmpty()
        && Set.of("утра", "дня", "вечера", "ночи").contains(tokens.get(tokens.size() - 1)))
      suffix = tokens.remove(tokens.size() - 1);
    if (tokens.equals(List.of("час"))) tokens = new ArrayList<>(List.of("один"));
    else tokens.removeIf(x -> x.equals("час") || x.equals("часа") || x.equals("часов"));
    tokens.removeIf(x -> x.equals("минут") || x.equals("минуты") || x.equals("минуту"));
    if (tokens.isEmpty()) return null;
    for (int split = tokens.size(); split >= 1; split--) {
      Integer hour = number(tokens.subList(0, split));
      if (hour == null || hour > 23) continue;
      Integer minute = 0;
      if (split < tokens.size()) {
        minute = number(tokens.subList(split, tokens.size()));
        if (minute == null || minute > 59) continue;
      }
      String digits = split < tokens.size() ? hour + ":" + String.format("%02d", minute) : "" + hour;
      return suffix == null ? digits : digits + " " + suffix;
    }
    return null;
  }

  public Period period(String expression, ZoneId zone) {
    String s = norm(expression);
    ZonedDateTime now = clock.instant().atZone(zone);
    LocalDate first, last;
    Instant from;
    if (s.matches("(?:(?:на|в) )?(?:(?:этой|эту|эта|текущую|текущей|текущая) )?недел[еюия]")) {
      first = now.toLocalDate();
      last = first.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
      from = now.toInstant();
    } else if (s.matches("(?:(?:на|в) )?следующ(?:ей|ую|ая) недел[еюия]")) {
      first = now.toLocalDate().with(TemporalAdjusters.next(DayOfWeek.MONDAY));
      last = first.plusDays(6);
      from = first.atStartOfDay(zone).toInstant();
    } else if (s.matches("(?:что у меня )?(?:в |за )?[а-я]+")
        && monthOf(s.replaceFirst("^(?:что у меня )?(?:в |за )?", "")) != 0) {
      int month = monthOf(s.replaceFirst("^(?:что у меня )?(?:в |за )?", "").replaceFirst("\\s+.*$", ""));
      first = LocalDate.of(now.getYear(), month, 1);
      last = first.with(TemporalAdjusters.lastDayOfMonth());
      from = first.atStartOfDay(zone).toInstant();
    } else {
      Matcher natural = Pattern.compile("(?:с\\s+)?(\\d{1,2})\\s+([а-я]+)\\s+(?:по|до)\\s+(\\d{1,2})\\s+([а-я]+)(?:\\s+(\\d{4}))?").matcher(s);
      if (natural.matches()) {
        int m1 = monthOf(natural.group(2)), m2 = monthOf(natural.group(4));
        if (m1 == 0 || m2 == 0) throw new IllegalArgumentException("period");
        int year = natural.group(5) == null ? now.getYear() : Integer.parseInt(natural.group(5));
        try {
          first = LocalDate.of(year, m1, Integer.parseInt(natural.group(1)));
          last = LocalDate.of(year + (m2 < m1 ? 1 : 0), m2, Integer.parseInt(natural.group(3)));
        } catch (DateTimeException ex) { throw new IllegalArgumentException("period"); }
        if (natural.group(5) == null && last.isBefore(now.toLocalDate().minusDays(62))) {
          first = first.plusYears(1); last = last.plusYears(1);
        }
        if (ChronoUnit.DAYS.between(first, last) >= 62 || last.isBefore(first)) throw new IllegalArgumentException("period");
        return new Period(first.atStartOfDay(zone).toInstant(), last.plusDays(1).atStartOfDay(zone).toInstant(), first, last);
      }
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
