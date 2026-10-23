package com.example.agend_ai.service;

import static org.assertj.core.api.Assertions.*;

import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class DateResolverTest {
  private static final ZoneId MSK = ZoneId.of("Europe/Moscow");
  private static final Instant NOW = Instant.parse("2026-10-23T11:02:47Z");
  private final DateResolver resolver = new DateResolver(Clock.fixed(NOW, ZoneOffset.UTC));

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "сегодня|15:00|2026-10-23T12:00:00Z", "завтра|10:00|2026-10-24T07:00:00Z",
        "послезавтра|11:00|2026-10-25T08:00:00Z", "в четверг|11|2026-10-29T08:00:00Z",
        "понедельник|9|2026-10-26T06:00:00Z", "вторник|9|2026-10-27T06:00:00Z",
        "в среду|9|2026-10-28T06:00:00Z", "суббота|9|2026-10-24T06:00:00Z",
        "воскресенье|9|2026-10-25T06:00:00Z", "завтра|в три|2026-10-24T12:00:00Z",
        "завтра|в 3|2026-10-24T12:00:00Z", "завтра|в один|2026-10-24T10:00:00Z",
        "завтра|в два|2026-10-24T11:00:00Z", "завтра|в семь|2026-10-24T16:00:00Z",
        "завтра|в восемь|2026-10-24T05:00:00Z", "завтра|в девять|2026-10-24T06:00:00Z",
        "завтра|в десять|2026-10-24T07:00:00Z", "завтра|в одиннадцать|2026-10-24T08:00:00Z",
        "завтра|в двенадцать|2026-10-24T09:00:00Z", "завтра|3 утра|2026-10-24T00:00:00Z",
        "завтра|3 дня|2026-10-24T12:00:00Z", "завтра|7 вечера|2026-10-24T16:00:00Z",
        "завтра|12 ночи|2026-10-23T21:00:00Z", "завтра|12 дня|2026-10-24T09:00:00Z",
        "завтра|03:30|2026-10-24T00:30:00Z", "завтра|15:30|2026-10-24T12:30:00Z",
        "2026-12-31|23:59|2026-12-31T20:59:00Z", "|через два часа|2026-10-23T13:02:00Z",
        "через 2 часа||2026-10-23T13:02:00Z", "|через 30 минут|2026-10-23T11:32:00Z"
      })
  void resolvesExactInstant(String date, String time, String expected) {
    var result = resolver.resolve(date, time, MSK, null);
    assertThat(result.startAt()).isEqualTo(Instant.parse(expected));
    assertThat(result.needsDateClarification()).isFalse();
    assertThat(result.needsTimeClarification()).isFalse();
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "до конца недели|2026-10-25T20:59:00Z",
        "конец недели|2026-10-25T20:59:00Z",
        "завтра|2026-10-24T20:59:00Z",
        "послезавтра|2026-10-25T20:59:00Z"
      })
  void dateOnlyHasDeadline(String date, String expected) {
    var result = resolver.resolve(date, null, MSK, null);
    assertThat(result.startAt()).isNull();
    assertThat(result.dueAt()).isEqualTo(Instant.parse(expected));
    assertThat(result.needsTimeClarification()).isTrue();
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "|missing_date",
        "в пятницу|ambiguous_date",
        "на следующей неделе|missing_day",
        "2026-02-30|invalid_date",
        "когда-нибудь|invalid_date",
        "завтрак|invalid_date"
      })
  void unknownAndAmbiguousDatesAsk(String expr, String reason) {
    var result = resolver.resolve(expr, "15:00", MSK, null);
    assertThat(result.needsDateClarification()).isTrue();
    assertThat(result.reason()).isEqualTo(reason);
    assertThat(result.startAt()).isNull();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "утром",
        "днем",
        "вечером",
        "25:00",
        "12:90",
        "13 вечера",
        "0 утра",
        "в три с чем-то"
      })
  void invalidTimeNeverGuessed(String time) {
    var result = resolver.resolve("завтра", time, MSK, null);
    assertThat(result.startAt()).isNull();
    assertThat(result.needsTimeClarification()).isTrue();
  }

  @Test
  void pastDateAndTimeAreFlagged() {
    assertThat(resolver.resolve("2026-10-22", "15:00", MSK, null).reason()).isEqualTo("past");
    assertThat(resolver.resolve("сегодня", "10:00", MSK, null).reason()).isEqualTo("past");
  }

  @Test
  void sundayDeadlineIsToday() {
    var res = resolver.resolve("до конца недели", null, MSK, Instant.parse("2026-10-25T11:00:00Z"));
    assertThat(res.dueAt()).isEqualTo(Instant.parse("2026-10-25T20:59:00Z"));
  }

  @Test
  void nextDayAcrossYear() {
    var res = resolver.resolve("завтра", "10:00", MSK, Instant.parse("2026-12-31T12:00:00Z"));
    assertThat(res.startAt()).isEqualTo(Instant.parse("2027-01-01T07:00:00Z"));
  }

  @Test
  void relativeAcrossMidnight() {
    var res = resolver.resolve(null, "через два часа", MSK, Instant.parse("2026-10-31T20:30:59Z"));
    assertThat(res.startAt()).isEqualTo(Instant.parse("2026-10-31T22:30:00Z"));
    assertThat(res.startAt().atZone(MSK).toLocalDate()).isEqualTo(LocalDate.of(2026, 11, 1));
  }

  @Test
  void leapYear() {
    var res = resolver.resolve("послезавтра", "10:00", MSK, Instant.parse("2028-02-28T11:00:00Z"));
    assertThat(res.startAt()).isEqualTo(Instant.parse("2028-03-01T07:00:00Z"));
  }

  @Test
  void isoTimestampPreservesOffset() {
    assertThat(resolver.resolve("2026-10-24T15:00:00+05:00", null, MSK, null).startAt())
        .isEqualTo(Instant.parse("2026-10-24T10:00:00Z"));
  }

  @Test
  void dstGapAndOverlapAsk() {
    ZoneId berlin = ZoneId.of("Europe/Berlin");
    assertThat(resolver.resolve("2027-03-28", "02:30", berlin, null).reason())
        .isEqualTo("ambiguous_time");
    assertThat(resolver.resolve("2026-10-25", "02:30", berlin, null).reason())
        .isEqualTo("ambiguous_time");
  }

  @Test
  void relativeAcrossDstIsElapsedTime() {
    assertThat(
            resolver
                .resolve(
                    null,
                    "через два часа",
                    ZoneId.of("Europe/Berlin"),
                    Instant.parse("2027-03-28T00:30:59Z"))
                .startAt())
        .isEqualTo(Instant.parse("2027-03-28T02:30:00Z"));
  }

  @Test
  void currentWeekStartsNowAndNextWeekIsMondaySunday() {
    var p = resolver.period("на этой неделе", MSK);
    assertThat(p.from()).isEqualTo(NOW);
    assertThat(p.to()).isEqualTo(Instant.parse("2026-10-25T21:00:00Z"));
    var next = resolver.period("на следующей неделе", MSK);
    assertThat(next.first()).isEqualTo(LocalDate.of(2026, 10, 26));
    assertThat(next.last()).isEqualTo(LocalDate.of(2026, 11, 1));
  }

  @Test
  void periodsValidateInclusive62DayLimit() {
    assertThat(resolver.period("2026-10-01/2026-12-01", MSK).last())
        .isEqualTo(LocalDate.of(2026, 12, 1));
    assertThatThrownBy(() -> resolver.period("2026-10-01/2026-12-02", MSK))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> resolver.period("2026-10-25/2026-10-24", MSK))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void timezoneChangesToday() {
    assertThat(
            resolver
                .resolve(
                    "сегодня",
                    "23:00",
                    ZoneId.of("Pacific/Kiritimati"),
                    Instant.parse("2026-10-23T22:00:00Z"))
                .startAt())
        .isEqualTo(Instant.parse("2026-10-24T09:00:00Z"));
  }

  @Test
  void dstPeriodUsesCalendarDays() {
    var p = resolver.period("2026-10-25", ZoneId.of("Europe/Berlin"));
    assertThat(Duration.between(p.from(), p.to())).isEqualTo(Duration.ofHours(25));
  }
}
