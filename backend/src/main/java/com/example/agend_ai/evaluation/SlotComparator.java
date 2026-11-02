package com.example.agend_ai.evaluation;

import com.example.agend_ai.service.DateResolver;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;

/** Compares slots semantically while delegating all temporal arithmetic to DateResolver. */
public final class SlotComparator {
  private static final Set<String> DATE_SLOTS =
      Set.of("date_expr", "deadline_expr", "new_date_expr");
  private static final Set<String> TIME_SLOTS =
      Set.of("time_expr", "new_time_expr", "end_time_expr");

  public Comparison compare(
      EvaluationDataset.Example example,
      JsonNode actualSlots,
      Instant now,
      ZoneId zone) {
    Map<String, JsonNode> expected =
        example.expectedSlots() == null ? Map.of() : example.expectedSlots();
    Set<String> keys = new TreeSet<>(expected.keySet());
    if (actualSlots != null && actualSlots.isObject()) {
      actualSlots.fields().forEachRemaining(e -> {
        if (isPresent(e.getValue())) keys.add(e.getKey());
      });
    }

    Set<String> critical =
        example.criticalSlots() == null ? Set.of() : new HashSet<>(example.criticalSlots());
    List<SlotResult> results = new ArrayList<>();
    for (String key : keys) {
      JsonNode exp = expected.get(key);
      JsonNode act = actualSlots == null ? null : actualSlots.get(key);
      boolean expectedPresent = isPresent(exp);
      boolean actualPresent = isPresent(act);
      Match match;
      if (!expectedPresent && !actualPresent) {
        match = new Match(true, "both_absent");
      } else if (!expectedPresent) {
        match = new Match(false, "unexpected_slot");
      } else if (!actualPresent) {
        match = new Match(false, "missing_slot");
      } else {
        match = valueMatches(key, exp.asText(), act.asText(), example, now, zone);
      }
      results.add(
          new SlotResult(
              key,
              expectedPresent ? exp.asText() : null,
              actualPresent ? act.asText() : null,
              match.matches(),
              critical.contains(key),
              match.method()));
    }
    return new Comparison(results);
  }

  private Match valueMatches(
      String slot,
      String expected,
      String actual,
      EvaluationDataset.Example example,
      Instant now,
      ZoneId zone) {
    if (norm(expected).equals(norm(actual))) return new Match(true, "normalized_text");

    DateResolver resolver = new DateResolver(Clock.fixed(now, ZoneOffset.UTC));
    if (DATE_SLOTS.contains(slot)) {
      String e = normalizedDate(resolver, expected, zone);
      String a = normalizedDate(resolver, actual, zone);
      if (e != null && e.equals(a)) return new Match(true, "date_resolver");
    }
    if (TIME_SLOTS.contains(slot)) {
      String e = normalizedTime(resolver, expected, zone, now);
      String a = normalizedTime(resolver, actual, zone, now);
      if (e != null && e.equals(a)) return new Match(true, "date_resolver");
    }
    if ("period_expr".equals(slot)) {
      String e = normalizedPeriod(resolver, expected, zone);
      String a = normalizedPeriod(resolver, actual, zone);
      if (e != null && e.equals(a)) return new Match(true, "date_resolver");
    }
    if ("answer".equals(slot)) {
      String eDecision = normalizedDecision(expected);
      String aDecision = normalizedDecision(actual);
      if (eDecision != null && eDecision.equals(aDecision)) {
        return new Match(true, "decision_alias");
      }
      String awaiting = pendingAwaiting(example);
      if ("time".equals(awaiting) || "end_time".equals(awaiting)) {
        String e = normalizedTime(resolver, expected, zone, now);
        String a = normalizedTime(resolver, actual, zone, now);
        if (e != null && e.equals(a)) return new Match(true, "date_resolver");
      }
      if ("date".equals(awaiting)) {
        String e = normalizedDate(resolver, expected, zone);
        String a = normalizedDate(resolver, actual, zone);
        if (e != null && e.equals(a)) return new Match(true, "date_resolver");
      }
      if ("item".equals(awaiting)) {
        String e = normalizedOrdinal(expected);
        String a = normalizedOrdinal(actual);
        if (e != null && e.equals(a)) return new Match(true, "ordinal_alias");
      }
    }
    return new Match(false, "mismatch");
  }

  private static String normalizedDate(DateResolver resolver, String value, ZoneId zone) {
    try {
      DateResolver.Period p = resolver.period(value, zone);
      return p.first() + "/" + p.last();
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private static String normalizedPeriod(DateResolver resolver, String value, ZoneId zone) {
    try {
      DateResolver.Period p = resolver.period(value, zone);
      return p.first() + "/" + p.last();
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private static String normalizedTime(
      DateResolver resolver, String value, ZoneId zone, Instant now) {
    try {
      DateResolver.ResolvedDateTime r = resolver.resolve("завтра", value, zone, now);
      Instant at = r.startAt() != null ? r.startAt() : r.dueAt();
      return r.reason() == null && at != null ? at.toString() : null;
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private static String pendingAwaiting(EvaluationDataset.Example example) {
    JsonNode pending = example.context() == null ? null : example.context().pending();
    if (pending == null || !pending.path("awaiting").isArray() || pending.path("awaiting").isEmpty()) {
      return null;
    }
    return pending.path("awaiting").get(0).asText(null);
  }


  private static boolean isPresent(JsonNode value) {
    if (value == null || value.isNull() || value.isMissingNode()) return false;
    return !value.isTextual() || !value.asText().isBlank();
  }

  private static String normalizedDecision(String value) {
    String n = norm(value);
    if (Set.of("yes", "да", "ага", "подтверждаю", "да подтверждаю").contains(n)) return "yes";
    if (Set.of("cancel", "no", "нет", "отмена", "не надо", "пока не надо").contains(n)) return "cancel";
    if (Set.of("keep_conflict", "все равно", "ставь все равно", "в это же", "все равно ставь").contains(n)) return "keep_conflict";
    if (Set.of("other_time", "выбери другое время", "выбрать другое время", "другое время").contains(n)) return "other_time";
    return null;
  }

  private static String normalizedOrdinal(String value) {
    String n = norm(value).replaceAll("[^0-9а-я ]", "");
    if (n.matches(".*\\b(1|первая|первую|первый|первое)\\b.*")) return "1";
    if (n.matches(".*\\b(2|вторая|вторую|второй|второе)\\b.*")) return "2";
    if (n.matches(".*\\b(3|третья|третью|третий|третье)\\b.*")) return "3";
    return null;
  }

  private static String norm(String value) {
    if (value == null) return "";
    return value
        .strip()
        .toLowerCase(Locale.ROOT)
        .replace('ё', 'е')
        .replace('–', '-')
        .replace('—', '-')
        .replaceAll("[.!?;,:]+$", "")
        .replaceAll("\\s+", " ");
  }

  private record Match(boolean matches, String method) {}

  public record SlotResult(
      String slot,
      String expected,
      String actual,
      boolean matches,
      boolean critical,
      String method) {}

  public record Comparison(List<SlotResult> slots) {
    public Comparison {
      slots = List.copyOf(slots);
    }

    public long total() {
      return slots.size();
    }

    public long matched() {
      return slots.stream().filter(SlotResult::matches).count();
    }

    public long criticalTotal() {
      return slots.stream().filter(SlotResult::critical).count();
    }

    public long criticalMatched() {
      return slots.stream().filter(s -> s.critical() && s.matches()).count();
    }

    public boolean allMatch() {
      return slots.stream().allMatch(SlotResult::matches);
    }
  }
}
