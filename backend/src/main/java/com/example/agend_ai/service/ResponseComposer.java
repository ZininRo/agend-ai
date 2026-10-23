package com.example.agend_ai.service;

import com.example.agend_ai.entity.Item;
import com.example.agend_ai.entity.enums.ItemType;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ResponseComposer {
  private final Clock clock;
  private static final Locale RU = Locale.forLanguageTag("ru");
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

  public String date(LocalDate date, ZoneId zone) {
    LocalDate today = clock.instant().atZone(zone).toLocalDate();
    if (date.equals(today)) return "сегодня";
    if (date.equals(today.plusDays(1))) return "завтра";
    if (date.equals(today.plusDays(2))) return "послезавтра";
    return date.format(DateTimeFormatter.ofPattern("d MMMM, EEEE", RU));
  }

  public String created(Item item, ZoneId zone) {
    ZonedDateTime at =
        (item.getType() == ItemType.TASK ? item.getDueAt() : item.getStartAt()).atZone(zone);
    String day = date(at.toLocalDate(), zone), time = at.format(TIME);
    return switch (item.getType()) {
      case REMINDER -> "Готово: напомню " + day + " в " + time + " " + item.getTitle() + ".";
      case MEETING ->
          "Готово: "
              + (item.getTitle().toLowerCase(RU).startsWith("встреча")
                  ? item.getTitle()
                  : "встреча " + item.getTitle())
              + " — "
              + day
              + ", "
              + at.format(DateTimeFormatter.ofPattern("d MMMM", RU))
              + ", в "
              + time
              + ".";
      case TASK ->
          "Готово: задача «"
              + item.getTitle()
              + "» — до "
              + at.format(DateTimeFormatter.ofPattern("d MMMM", RU))
              + ", "
              + time
              + ".";
    };
  }

  public String list(List<Item> items, DateResolver.Period period, ZoneId zone, String type) {
    if (items.isEmpty()) {
      String noun =
          "task".equals(type)
              ? "задач"
              : "meeting".equals(type)
                  ? "встреч"
                  : "reminder".equals(type) ? "напоминаний" : "записей";
      String when =
          period.first().equals(period.last())
              ? date(period.first(), zone)
              : period.first() + " — " + period.last();
      return "На " + when + " " + noun + " нет.";
    }
    StringBuilder result =
        new StringBuilder("Записи за период " + period.first() + " — " + period.last() + ":");
    LocalDate previous = null;
    for (Item item : items) {
      ZonedDateTime at =
          (item.getType() == ItemType.TASK ? item.getDueAt() : item.getStartAt()).atZone(zone);
      if (!at.toLocalDate().equals(previous)) {
        previous = at.toLocalDate();
        result.append("\n").append(date(previous, zone)).append(":");
      }
      result.append("\n").append(at.format(TIME)).append(" — ").append(item.getTitle());
    }
    return result.toString();
  }

  public String composeRefused() {
    return "Этого я пока не умею. Могу создавать задачи, напоминания и встречи, показывать списки и"
               + " искать записи.";
  }
}
