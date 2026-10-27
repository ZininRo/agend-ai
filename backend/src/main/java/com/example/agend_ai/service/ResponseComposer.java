package com.example.agend_ai.service;

import com.example.agend_ai.entity.Item;
import com.example.agend_ai.entity.enums.ItemStatus;
import com.example.agend_ai.entity.enums.ItemType;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Тексты ответов строятся только из шаблонов и реальных данных БД (разд. 11.4 ТЗ). */
@Component
@RequiredArgsConstructor
public class ResponseComposer {
  private final Clock clock;
  private static final Locale RU = Locale.forLanguageTag("ru");
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
  private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("d MMMM", RU);
  private static final DateTimeFormatter WEEKDAY = DateTimeFormatter.ofPattern("EEEE", RU);
  private static final String[] WEEKDAY_GENITIVE = {
    "понедельника", "вторника", "среды", "четверга", "пятницы", "субботы", "воскресенья"
  };
  /** Срок «на день» хранится как 23:59 локального времени. */
  private static final LocalTime END_OF_DAY = LocalTime.of(23, 59);

  private LocalDate today(ZoneId zone) {
    return clock.instant().atZone(zone).toLocalDate();
  }

  /** «сегодня», «завтра», «послезавтра», иначе «30 октября, пятница». */
  public String date(LocalDate date, ZoneId zone) {
    String word = nearDay(date, zone);
    return word != null ? word : DAY_MONTH.format(date) + ", " + WEEKDAY.format(date);
  }

  private String nearDay(LocalDate date, ZoneId zone) {
    long delta = ChronoUnit.DAYS.between(today(zone), date);
    if (delta == 0) return "сегодня";
    if (delta == 1) return "завтра";
    if (delta == 2) return "послезавтра";
    return null;
  }

  /** «завтра, 24 октября» или «30 октября, пятница» (без повтора даты). */
  private String fullDay(LocalDate date, ZoneId zone) {
    String word = nearDay(date, zone);
    return word != null ? word + ", " + DAY_MONTH.format(date) : date(date, zone);
  }

  /** «завтра» или «30 октября» — для напоминаний. */
  private String dayWord(LocalDate date, ZoneId zone) {
    String word = nearDay(date, zone);
    return word != null ? word : DAY_MONTH.format(date);
  }

  private String deadline(LocalDate date, ZoneId zone) {
    String word = nearDay(date, zone);
    String dm = DAY_MONTH.format(date);
    if (word == null) return "до " + WEEKDAY_GENITIVE[date.getDayOfWeek().getValue() - 1] + ", " + dm;
    return switch (word) {
      case "сегодня" -> "до конца дня, " + dm;
      case "завтра" -> "до завтра, " + dm;
      default -> "до послезавтра, " + dm;
    };
  }

  private static String lowerFirst(String s) {
    if (s == null || s.length() < 2 || Character.isUpperCase(s.charAt(1))) return s;
    return Character.toLowerCase(s.charAt(0)) + s.substring(1);
  }

  private static String meetingName(String title) {
    String lower = title.toLowerCase(RU);
    return lower.startsWith("с ") || lower.startsWith("со ") ? "встреча " + title : lowerFirst(title);
  }

  public String created(Item item, ZoneId zone) {
    ZonedDateTime at =
        (item.getType() == ItemType.TASK ? item.getDueAt() : item.getStartAt()).atZone(zone);
    LocalDate day = at.toLocalDate();
    String time = at.format(TIME);
    return switch (item.getType()) {
      case REMINDER ->
          "Готово: напомню "
              + dayWord(day, zone)
              + " в "
              + time
              + " "
              + lowerFirst(item.getTitle())
              + ".";
      case MEETING ->
          "Готово: "
              + meetingName(item.getTitle())
              + " — "
              + fullDay(day, zone)
              + ", в "
              + time
              + ".";
      case TASK ->
          "Готово: задача «"
              + item.getTitle()
              + "» — "
              + deadline(day, zone)
              + (at.toLocalTime().equals(END_OF_DAY) ? "" : ", в " + time)
              + ".";
    };
  }

  public String list(List<Item> items, DateResolver.Period period, ZoneId zone, String type) {
    LocalDate today = today(zone);
    if (items.isEmpty()) {
      return "На " + emptyPeriod(period, today, zone) + " " + noun(type, 0) + " нет.";
    }
    boolean oneDay = period.first().equals(period.last());
    if (oneDay && type == null) return mixedOneDay(items, period, zone);
    String header = header(period, today, zone) + " " + items.size() + " " + noun(type, items.size()) + ":";
    if (oneDay) {
      List<String> parts = new ArrayList<>();
      for (int i = 0; i < items.size(); i++) parts.add((i + 1) + ") " + line(items.get(i), zone));
      return header + " " + String.join("; ", parts) + ".";
    }
    Map<LocalDate, List<String>> byDay = new LinkedHashMap<>();
    for (Item item : items) {
      LocalDate day =
          (item.getType() == ItemType.TASK ? item.getDueAt() : item.getStartAt())
              .atZone(zone)
              .toLocalDate();
      byDay.computeIfAbsent(day, k -> new ArrayList<>()).add(line(item, zone));
    }
    StringBuilder result = new StringBuilder(header);
    byDay.forEach(
        (day, lines) ->
            result.append("\n").append(date(day, zone)).append(": ").append(String.join("; ", lines)).append("."));
    return result.toString();
  }

  private String mixedOneDay(List<Item> items, DateResolver.Period period, ZoneId zone) {
    Map<ItemType, List<Item>> groups = new LinkedHashMap<>();
    groups.put(ItemType.MEETING, new ArrayList<>());
    groups.put(ItemType.TASK, new ArrayList<>());
    groups.put(ItemType.REMINDER, new ArrayList<>());
    items.forEach(i -> groups.get(i.getType()).add(i));
    List<String> sections = new ArrayList<>();
    for (var entry : groups.entrySet()) {
      List<Item> group = entry.getValue();
      if (group.isEmpty()) continue;
      String type = entry.getKey().name().toLowerCase(Locale.ROOT);
      List<String> lines = new ArrayList<>();
      for (int i = 0; i < group.size(); i++) {
        Item item = group.get(i);
        String line;
        if (item.getType() == ItemType.TASK) {
          line = item.getTitle();
          if (item.getStatus() == ItemStatus.DONE) line += " (выполнено)";
        } else line = line(item, zone);
        lines.add((i + 1) + ") " + line);
      }
      sections.add(group.size() + " " + noun(type, group.size()) + ": " + String.join("; ", lines));
    }
    String prefix = header(period, today(zone), zone);
    return prefix + " " + String.join(". И ", sections) + ".";
  }

  private String line(Item item, ZoneId zone) {
    ZonedDateTime at =
        (item.getType() == ItemType.TASK ? item.getDueAt() : item.getStartAt()).atZone(zone);
    String text =
        item.getType() == ItemType.TASK && at.toLocalTime().equals(END_OF_DAY)
            ? item.getTitle() + " (задача)"
            : at.format(TIME) + " — " + item.getTitle();
    return item.getStatus() == ItemStatus.DONE ? text + " (выполнено)" : text;
  }

  private String header(DateResolver.Period p, LocalDate today, ZoneId zone) {
    if (p.first().equals(p.last())) {
      long delta = ChronoUnit.DAYS.between(today, p.first());
      if (delta == -1) return "Вчера";
      String word = nearDay(p.first(), zone);
      return word != null ? Character.toUpperCase(word.charAt(0)) + word.substring(1) : "На " + DAY_MONTH.format(p.first());
    }
    if (isThisWeek(p, today)) return "На этой неделе";
    if (isNextWeek(p, today)) return "На следующей неделе";
    return "С " + DAY_MONTH.format(p.first()) + " по " + DAY_MONTH.format(p.last());
  }

  private String emptyPeriod(DateResolver.Period p, LocalDate today, ZoneId zone) {
    if (p.first().equals(p.last())) {
      long delta = ChronoUnit.DAYS.between(today, p.first());
      if (delta == -1) return "вчера";
      return dayWord(p.first(), zone);
    }
    if (isThisWeek(p, today)) return "эту неделю";
    if (isNextWeek(p, today)) return "следующую неделю";
    return "период с " + DAY_MONTH.format(p.first()) + " по " + DAY_MONTH.format(p.last());
  }

  private static boolean isThisWeek(DateResolver.Period p, LocalDate today) {
    return p.first().equals(today)
        && p.last().equals(today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)));
  }

  private static boolean isNextWeek(DateResolver.Period p, LocalDate today) {
    LocalDate monday = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
    return p.first().equals(monday) && p.last().equals(monday.plusDays(6));
  }

  private static String noun(String type, int n) {
    int n100 = n % 100, n10 = n % 10;
    int form = (n100 >= 11 && n100 <= 14) ? 2 : n10 == 1 ? 0 : (n10 >= 2 && n10 <= 4) ? 1 : 2;
    String[] forms =
        "task".equals(type)
            ? new String[] {"задача", "задачи", "задач"}
            : "meeting".equals(type)
                ? new String[] {"встреча", "встречи", "встреч"}
                : "reminder".equals(type)
                    ? new String[] {"напоминание", "напоминания", "напоминаний"}
                    : new String[] {"запись", "записи", "записей"};
    return forms[form];
  }

  public String updated(Item item, ZoneId zone) {
    Instant instant = item.getType() == ItemType.TASK ? item.getDueAt() : item.getStartAt();
    if (instant == null) return "Обновил запись «" + item.getTitle() + "».";
    ZonedDateTime at = instant.atZone(zone);
    if (item.getType() == ItemType.MEETING)
      return "Перенёс " + meetingName(item.getTitle()) + " на " + at.format(TIME) + " — " + fullDay(at.toLocalDate(), zone) + ".";
    if (item.getType() == ItemType.REMINDER)
      return "Перенёс напоминание «" + item.getTitle() + "» на " + dayWord(at.toLocalDate(), zone) + " в " + at.format(TIME) + ".";
    return "Изменил срок задачи «" + item.getTitle() + "» — " + deadline(at.toLocalDate(), zone)
        + (at.toLocalTime().equals(END_OF_DAY) ? "" : ", в " + at.format(TIME)) + ".";
  }

  public String completed(Item item) {
    return "Отметил выполненной: " + lowerFirst(item.getTitle()) + ".";
  }

  public String renamed(Item item) {
    return "Название " + (item.getType() == ItemType.TASK ? "задачи" : item.getType() == ItemType.MEETING ? "встречи" : "напоминания")
        + " успешно изменено на «" + item.getTitle() + "».";
  }

  public String deleteConfirmation(Item item, ZoneId zone) {
    Instant instant = item.getType() == ItemType.TASK ? item.getDueAt() : item.getStartAt();
    String when = instant == null ? "" : " на " + dayWord(instant.atZone(zone).toLocalDate(), zone);
    return "Вы хотите удалить " + (item.getType() == ItemType.TASK ? "задачу" : item.getType() == ItemType.MEETING ? "встречу" : "напоминание")
        + " «" + item.getTitle() + "»" + when + "?";
  }

  public String deleted(Item item) {
    return switch (item.getType()) {
      case TASK -> "Задача успешно удалена.";
      case MEETING -> "Встреча успешно удалена.";
      case REMINDER -> "Напоминание успешно удалено.";
    };
  }

  public String conflict(Item conflict, ZoneId zone) {
    ZonedDateTime at = conflict.getStartAt().atZone(zone);
    return "На это время уже запланирована встреча «" + conflict.getTitle() + "» ("
        + dayWord(at.toLocalDate(), zone) + " в " + at.format(TIME)
        + "). Выбрать другое время или поставить в это же?";
  }

  public String search(List<Item> found, ZoneId zone) {
    if (found.isEmpty()) return "Не нашёл такую запись. Уточните название.";
    List<String> parts = new ArrayList<>();
    for (int i = 0; i < found.size(); i++) {
      Item item = found.get(i);
      Instant instant = item.getType() == ItemType.TASK ? item.getDueAt() : item.getStartAt();
      String when = instant == null ? "без даты" : fullDay(instant.atZone(zone).toLocalDate(), zone)
          + (item.getType() == ItemType.TASK && instant.atZone(zone).toLocalTime().equals(END_OF_DAY)
              ? "" : ", в " + instant.atZone(zone).format(TIME));
      parts.add((i + 1) + ") " + item.getTitle() + " — " + when);
    }
    return found.size() == 1 ? "Нашёл: " + parts.get(0).substring(3) + "."
        : "Нашёл " + found.size() + " записи: " + String.join("; ", parts) + ".";
  }

  public String chooseItem(List<Item> found, ZoneId zone) {
    List<String> parts = new ArrayList<>();
    for (int i = 0; i < found.size(); i++) {
      Item item = found.get(i);
      Instant instant = item.getType() == ItemType.TASK ? item.getDueAt() : item.getStartAt();
      String when = instant == null ? "без даты" : dayWord(instant.atZone(zone).toLocalDate(), zone);
      parts.add((i + 1) + ") " + item.getTitle() + " — " + when);
    }
    return "Нашёл несколько записей: " + String.join("; ", parts) + ". Какая из них?";
  }

  public String composeRefused() {
    return "Этого я пока не умею. Могу создавать задачи, напоминания и встречи, показывать списки и"
        + " искать записи.";
  }

  /** Интент распознан, но выполняется в следующих версиях (поиск, статус, перенос). */
  public String composeNotYet() {
    return "Эту команду я пока не умею выполнять. Сейчас могу создавать задачи, напоминания и"
        + " встречи и показывать списки записей.";
  }

  /** Ответы на уточняющие вопросы появятся позже: просим повторить запрос целиком. */
  public String composeAnswersNotSupported() {
    return "Продолжать прошлый вопрос я пока не умею. Повторите запрос целиком, например:"
        + " «Поставь встречу с Иваном завтра в 15:00».";
  }
}