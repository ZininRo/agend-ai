package com.example.agend_ai.service;

import static org.assertj.core.api.Assertions.*;

import com.example.agend_ai.entity.Item;
import com.example.agend_ai.entity.enums.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Тексты ответов (разд. 11.4 ТЗ). «Сейчас»: пятница, 23.10.2026, 14:02:47 (Europe/Moscow). */
class ResponseComposerTest {
  private static final ZoneId MSK = ZoneId.of("Europe/Moscow");
  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-23T11:02:47Z"), ZoneOffset.UTC);
  private final ResponseComposer composer = new ResponseComposer(CLOCK);
  private final DateResolver dates = new DateResolver(CLOCK);

  private static Item item(ItemType type, String title, String at) {
    Item i = new Item();
    i.setType(type);
    i.setTitle(title);
    if (type == ItemType.TASK) i.setDueAt(Instant.parse(at));
    else i.setStartAt(Instant.parse(at));
    return i;
  }

  @Test
  void createdMeeting() {
    assertThat(composer.created(item(ItemType.MEETING, "Встреча с командой", "2026-10-24T12:00:00Z"), MSK))
        .isEqualTo("Готово: встреча с командой — завтра, 24 октября, в 15:00.");
    assertThat(composer.created(item(ItemType.MEETING, "с командой", "2026-10-24T12:00:00Z"), MSK))
        .isEqualTo("Готово: встреча с командой — завтра, 24 октября, в 15:00.");
  }

  @Test
  void createdMeetingFarDateHasNoDuplicatedDate() {
    assertThat(composer.created(item(ItemType.MEETING, "Встреча с дизайнером", "2026-10-29T08:00:00Z"), MSK))
        .isEqualTo("Готово: встреча с дизайнером — 29 октября, четверг, в 11:00.");
  }

  @Test
  void createdReminder() {
    assertThat(composer.created(item(ItemType.REMINDER, "Позвонить Ивану", "2026-10-24T07:00:00Z"), MSK))
        .isEqualTo("Готово: напомню завтра в 10:00 позвонить Ивану.");
    assertThat(composer.created(item(ItemType.REMINDER, "Выпить воду", "2026-10-23T13:02:00Z"), MSK))
        .isEqualTo("Готово: напомню сегодня в 16:02 выпить воду.");
    assertThat(composer.created(item(ItemType.REMINDER, "Позвонить Ивану", "2026-10-30T07:00:00Z"), MSK))
        .isEqualTo("Готово: напомню 30 октября в 10:00 позвонить Ивану.");
  }

  @Test
  void createdTaskHidesServiceTime2359() {
    assertThat(composer.created(item(ItemType.TASK, "Отправить отчёт", "2026-10-30T20:59:00Z"), MSK))
        .isEqualTo("Готово: задача «Отправить отчёт» — до пятницы, 30 октября.");
    assertThat(composer.created(item(ItemType.TASK, "Отчёт", "2026-10-24T20:59:00Z"), MSK))
        .isEqualTo("Готово: задача «Отчёт» — до завтра, 24 октября.");
    assertThat(composer.created(item(ItemType.TASK, "Отчёт", "2026-10-23T20:59:00Z"), MSK))
        .isEqualTo("Готово: задача «Отчёт» — до конца дня, 23 октября.");
  }

  @Test
  void createdTaskWithExplicitTimeShowsIt() {
    assertThat(composer.created(item(ItemType.TASK, "Отчёт", "2026-10-24T07:00:00Z"), MSK))
        .isEqualTo("Готово: задача «Отчёт» — до завтра, 24 октября, в 10:00.");
  }

  @Test
  void emptyLists() {
    assertThat(composer.list(List.of(), dates.period("завтра", MSK), MSK, "task"))
        .isEqualTo("На завтра задач нет.");
    assertThat(composer.list(List.of(), dates.period("сегодня", MSK), MSK, null))
        .isEqualTo("На сегодня записей нет.");
    assertThat(composer.list(List.of(), dates.period("на этой неделе", MSK), MSK, null))
        .isEqualTo("На эту неделю записей нет.");
    assertThat(composer.list(List.of(), dates.period("на следующей неделе", MSK), MSK, "meeting"))
        .isEqualTo("На следующую неделю встреч нет.");
    assertThat(composer.list(List.of(), dates.period("2026-10-30", MSK), MSK, "reminder"))
        .isEqualTo("На 30 октября напоминаний нет.");
  }

  @Test
  void singleDayListIsNumberedLikeInSpec() {
    var list =
        List.of(
            item(ItemType.REMINDER, "Позвонить Ивану", "2026-10-23T07:00:00Z"),
            item(ItemType.MEETING, "Встреча с командой", "2026-10-23T12:00:00Z"));
    assertThat(composer.list(list, dates.period("сегодня", MSK), MSK, null))
        .isEqualTo("Сегодня 2 записи: 1) 10:00 — Позвонить Ивану; 2) 15:00 — Встреча с командой.");
    assertThat(composer.list(List.of(list.get(1)), dates.period("сегодня", MSK), MSK, "meeting"))
        .isEqualTo("Сегодня 1 встреча: 1) 15:00 — Встреча с командой.");
  }

  @Test
  void pluralFormsFollowRussianRules() {
    List<Item> five = new ArrayList<>();
    for (int i = 0; i < 5; i++) five.add(item(ItemType.TASK, "Т" + i, "2026-10-23T20:59:00Z"));
    assertThat(composer.list(five, dates.period("сегодня", MSK), MSK, "task")).startsWith("Сегодня 5 задач:");
    assertThat(composer.list(five.subList(0, 2), dates.period("сегодня", MSK), MSK, "task"))
        .startsWith("Сегодня 2 задачи:");
  }

  @Test
  void weekListIsGroupedByDay() {
    var list =
        List.of(
            item(ItemType.MEETING, "Сегодня", "2026-10-23T12:00:00Z"),
            item(ItemType.TASK, "Завтра", "2026-10-24T20:59:00Z"));
    assertThat(composer.list(list, dates.period("на этой неделе", MSK), MSK, null))
        .isEqualTo("На этой неделе 2 записи:\nсегодня: 15:00 — Сегодня.\nзавтра: Завтра (задача).");
  }

  @Test
  void doneItemsAreMarked() {
    Item done = item(ItemType.TASK, "Отчёт", "2026-10-23T07:00:00Z");
    done.setStatus(ItemStatus.DONE);
    assertThat(composer.list(List.of(done), dates.period("сегодня", MSK), MSK, "task"))
        .isEqualTo("Сегодня 1 задача: 1) 10:00 — Отчёт (выполнено).");
  }
}
