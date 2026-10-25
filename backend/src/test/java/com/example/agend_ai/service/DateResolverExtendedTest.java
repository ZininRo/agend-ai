package com.example.agend_ai.service;

import static org.assertj.core.api.Assertions.*;

import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/** Дополнительные случаи DateResolver. «Сейчас»: пятница, 23.10.2026, 14:02:47 (Europe/Moscow). */
class DateResolverExtendedTest {
  private static final ZoneId MSK = ZoneId.of("Europe/Moscow");
  private static final Instant NOW = Instant.parse("2026-10-23T11:02:47Z");
  private final DateResolver resolver = new DateResolver(Clock.fixed(NOW, ZoneOffset.UTC));

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        // предлоги и знаки препинания
        "на завтра|10:00|2026-10-24T07:00:00Z",
        "на сегодня|15:00|2026-10-23T12:00:00Z",
        "завтра.|10:00|2026-10-24T07:00:00Z",
        // дни недели: «до», «к», «следующая»
        "до четверга|10:00|2026-10-29T07:00:00Z",
        "к четвергу|10:00|2026-10-29T07:00:00Z",
        "следующая пятница|10:00|2026-10-30T07:00:00Z",
        "в эту пятницу|23:00|2026-10-23T20:00:00Z",
        // календарные даты
        "30 октября|11:00|2026-10-30T08:00:00Z",
        "30.10|11:00|2026-10-30T08:00:00Z",
        "30.10.2026|11:00|2026-10-30T08:00:00Z",
        "5 ноября 2027|11:00|2027-11-05T08:00:00Z",
        "3 января|10:00|2027-01-03T07:00:00Z",
        // «через N дней/недель»
        "через три дня|10:00|2026-10-26T07:00:00Z",
        "через неделю|10:00|2026-10-30T07:00:00Z",
        "через 2 недели|10:00|2026-11-06T07:00:00Z",
        // ISO без смещения — в часовом поясе пользователя
        "2026-10-24T15:00:00||2026-10-24T12:00:00Z",
        // время словами и с «часов»
        "завтра|в пятнадцать|2026-10-24T12:00:00Z",
        "завтра|в три тридцать|2026-10-24T12:30:00Z",
        "завтра|в десять сорок пять|2026-10-24T07:45:00Z",
        "завтра|в три часа дня|2026-10-24T12:00:00Z",
        "завтра|10 часов утра|2026-10-24T07:00:00Z",
        "завтра|в двадцать один|2026-10-24T18:00:00Z",
        // «3:30» без утра/вечера — дневное время, «03:30» — буквально
        "завтра|3:30|2026-10-24T12:30:00Z",
        "завтра|07:30|2026-10-24T04:30:00Z",
        "завтра|на 17|2026-10-24T14:00:00Z",
        "завтра|в 17.|2026-10-24T14:00:00Z"
      })
  void resolvesNewPhrases(String date, String time, String expected) {
    var result = resolver.resolve(date, time, MSK, null);
    assertThat(result.startAt()).isEqualTo(Instant.parse(expected));
    assertThat(result.needsDateClarification()).isFalse();
    assertThat(result.needsTimeClarification()).isFalse();
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "через час|2026-10-23T12:02:00Z",
        "через полчаса|2026-10-23T11:32:00Z",
        "через пять минут|2026-10-23T11:07:00Z",
        "через пятнадцать минут|2026-10-23T11:17:00Z",
        "через двадцать пять минут|2026-10-23T11:27:00Z",
        "через четыре часа|2026-10-23T15:02:00Z",
        "через 2 часа 15 минут|2026-10-23T13:17:00Z",
        "через полтора часа|2026-10-23T12:32:00Z"
      })
  void relativeTimeVariants(String time, String expected) {
    var result = resolver.resolve(null, time, MSK, null);
    assertThat(result.startAt()).isEqualTo(Instant.parse(expected));
    assertThat(result.needsDateClarification()).isFalse();
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "до пятницы|ambiguous_date",
        "12 октября|past",
        "31 февраля|invalid_date",
        "на этой неделе|missing_day",
        "через абракадабру|invalid_date",
        "через 0 минут|invalid_date"
      })
  void stillAsksWhenUnsure(String expr, String reason) {
    var result = resolver.resolve(expr.startsWith("через") ? null : expr, expr.startsWith("через") ? expr : "10:00", MSK, null);
    assertThat(result.reason()).isEqualTo(reason);
  }

  @ParameterizedTest
  @ValueSource(strings = {"к концу недели", "до конца этой недели", "конец недели"})
  void endOfWeekPhrases(String expr) {
    assertThat(resolver.resolve(expr, null, MSK, null).dueAt())
        .isEqualTo(Instant.parse("2026-10-25T20:59:00Z"));
  }

  @Test
  void localIsoTimestampInDstGapAsks() {
    assertThat(resolver.resolve("2027-03-28T02:30:00", null, ZoneId.of("Europe/Berlin"), null).reason())
        .isEqualTo("ambiguous_time");
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "на сегодня|2026-10-23|2026-10-23",
        "на завтра|2026-10-24|2026-10-24",
        "в субботу|2026-10-24|2026-10-24",
        "эта неделя|2026-10-23|2026-10-25",
        "на эту неделю|2026-10-23|2026-10-25",
        "следующая неделя|2026-10-26|2026-11-01",
        "на следующую неделю|2026-10-26|2026-11-01",
        "30 октября|2026-10-30|2026-10-30",
        "2026-10-24 по 2026-10-27|2026-10-24|2026-10-27"
      })
  void periodPhrases(String expr, String first, String last) {
    var p = resolver.period(expr, MSK);
    assertThat(p.first()).isEqualTo(LocalDate.parse(first));
    assertThat(p.last()).isEqualTo(LocalDate.parse(last));
  }
}
