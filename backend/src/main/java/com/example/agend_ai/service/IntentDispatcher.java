package com.example.agend_ai.service;

import com.example.agend_ai.api.dto.*;
import com.example.agend_ai.api.error.*;
import com.example.agend_ai.common.DefaultUser;
import com.example.agend_ai.config.LimitsProperties;
import com.example.agend_ai.entity.*;
import com.example.agend_ai.entity.enums.*;
import com.example.agend_ai.repository.*;
import com.example.agend_ai.service.llm.*;
import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class IntentDispatcher {
  private final LlmClient llmClient;
  private final IntentValidator validator;
  private final PromptBuilder prompts;
  private final DateResolver dates;
  private final ResponseComposer composer;
  private final ConflictChecker conflicts;
  private final ItemSearchService search;
  private final com.example.agend_ai.config.AppProperties app;
  private final ZoneResolver zones;
  private final LimitsProperties limits;
  private final Clock clock;
  private final ChatSessionRepository sessions;
  private final ItemRepository items;
  private final MessageRepository messages;
  private final UserRepository users;
  private final ObjectMapper mapper;
  private static final Pattern INJECTION =
      Pattern.compile(
          "(?ius)(игнорируй|забудь|ignore|disregard).{0,60}(инструкц|правил|prompt|instruction)|(?:system|assistant)\\s*:|раскрой.{0,30}(промпт|ключ|секрет)");

  @Transactional
  public MessageResponse process(MessageRequest raw) {
    if (raw.sessionId() == null
        || raw.sessionId().version() != 4
        || raw.sessionId().variant() != 2
        || raw.text() == null
        || (raw.source() != null && !Set.of("voice", "text").contains(raw.source())))
      throw new AppException(ErrorCode.REQUEST_INVALID);
    String text = raw.text().strip();
    if (text.isEmpty()) throw new AppException(ErrorCode.TEXT_EMPTY);
    if (text.codePointCount(0, text.length()) > limits.maxTextChars())
      throw new AppException(ErrorCode.TEXT_TOO_LONG);
    ZoneId zone = zones.resolve(raw.timezone());
    MessageRequest request =
        new MessageRequest(
            raw.sessionId(), text, zone.getId(), raw.source(), raw.clientMessageId());
    // Atomic creation + row lock also serializes first requests for an unknown session.
    sessions.ensure(request.sessionId(), DefaultUser.ID);
    ChatSession session = sessions.findByIdForUpdate(request.sessionId()).orElseThrow();
    if (session.getPendingClarification() != null && pendingExpired(session.getPendingClarification()))
      session.setPendingClarification(null);
    if (request.clientMessageId() != null) {
      var previous =
          messages.findBySessionIdAndClientMessageId(session.getId(), request.clientMessageId());
      if (previous.isPresent() && previous.get().getResponseJson() != null)
        return mapper.convertValue(previous.get().getResponseJson(), MessageResponse.class);
    }
    if (INJECTION.matcher(text).find())
      return finish(
          session,
          request,
          "refused",
          "unknown",
          composer.composeRefused(),
          List.of(),
          false,
          null);
    JsonNode payload = null;
    String data = prompts.data(session, text, zone, clock.instant());
    for (int attempt = 0; attempt < 2; attempt++) {
      String generated =
          llmClient.generateStructuredJson(
              prompts.system()
                  + (attempt == 0
                      ? ""
                      : "\nПредыдущий ответ нарушил схему. Верни корректный JSON."),
              data);
      try {
        // Модель может обернуть JSON в ```json …``` или вернуть пустой слот ""; убираем это до проверки.
        payload = validator.parse(ModelOutputCleaner.clean(generated, mapper));
        break;
      } catch (IllegalArgumentException e) {
        // Причину пишем всегда, а сам ответ модели (там может быть текст пользователя) — только в DEBUG.
        log.warn(
            "NLU: ответ модели отклонён (попытка {} из 2): {}; длина ответа {} симв.",
            attempt + 1,
            e.getMessage(),
            generated == null ? 0 : generated.length());
        if (log.isDebugEnabled())
          log.debug(
              "NLU: ответ модели: {}",
              generated == null
                  ? "null"
                  : generated.length() <= 1000 ? generated : generated.substring(0, 1000) + "…");
      }
    }
    if (payload == null)
      return finish(
          session,
          request,
          "refused",
          "unknown",
          "Не получилось понять запрос. Попробуйте сформулировать иначе.",
          List.of(),
          false,
          null);
    Intent intent = Intent.valueOf(payload.get("intent").asText());
    if (payload.get("confidence").asDouble() < 0.5)
      return finish(
          session,
          request,
          "refused",
          "unknown",
          "Не понял запрос. Скажите, например: «Напомни завтра в 10 позвонить Ивану».",
          List.of(),
          false,
          payload);
    return switch (intent) {
      case create_item -> { session.setPendingClarification(null); yield create(session, request, payload, zone); }
      case list_items -> { session.setPendingClarification(null); yield list(session, request, payload, zone); }
      case search_items -> { session.setPendingClarification(null); yield search(session, request, payload, zone); }
      case update_item -> { session.setPendingClarification(null); yield update(session, request, payload, zone); }
      case complete_item -> { session.setPendingClarification(null); yield complete(session, request, payload, zone); }
      case clarification_answer -> clarificationAnswer(session, request, payload, zone);
      case unknown ->
          finish(
              session,
              request,
              "refused",
              "unknown",
              "Не понял запрос. Скажите, например: «Напомни завтра в 10 позвонить Ивану».",
              List.of(),
              false,
              payload);
      case delete_item -> { session.setPendingClarification(null); yield delete(session, request, payload, zone); }
      default ->
          finish(
              session,
              request,
              "refused",
              intent.name(),
              composer.composeNotYet(),
              List.of(),
              false,
              payload);
    };
  }

  /** «позвонить Ивану» → «Позвонить Ивану»; для встречи «с командой» → «Встреча с командой». */
  private static String normalizeTitle(String raw, ItemType type) {
    String t = raw.strip().replaceAll("\\s+", " ").replaceAll("[.!?]+$", "").strip();
    if (t.isEmpty()) return raw;
    String lower = t.toLowerCase(Locale.ROOT);
    if (type == ItemType.MEETING && (lower.startsWith("с ") || lower.startsWith("со ")))
      t = "Встреча " + t;
    t = Character.toUpperCase(t.charAt(0)) + t.substring(1);
    return t.length() > 255 ? t.substring(0, 255) : t;
  }

  private static String slot(JsonNode slots, String name) {
    JsonNode n = slots.get(name);
    return n == null || n.isNull() ? null : n.asText().strip();
  }

  private MessageResponse create(
      ChatSession session, MessageRequest request, JsonNode payload, ZoneId zone) {
    return create(session, request, payload, zone, false);
  }

  private MessageResponse create(
      ChatSession session, MessageRequest request, JsonNode payload, ZoneId zone, boolean allowConflict) {
    JsonNode slots = payload.get("slots");
    String typeText = slot(slots, "item_type"), title = slot(slots, "title");
    if (typeText == null)
      return clarify(
          session,
          request,
          payload,
          "missing_slot",
          "item_type",
          "Что создать: задачу, встречу или напоминание?");
    ItemType type = ItemType.valueOf(typeText.toUpperCase(Locale.ROOT));
    if (title == null)
      return clarify(session, request, payload, "missing_slot", "title", "Как назвать запись?");
    title = normalizeTitle(title, type);
    String date = slot(slots, "date_expr"), time = slot(slots, "time_expr");
    String deadline = slot(slots, "deadline_expr");
    if (deadline != null && (type == ItemType.TASK || date == null)) date = deadline;
    var resolved = dates.resolve(date, time, zone, null);
    if (resolved.needsDateClarification()) {
      String reason = resolved.reason();
      String question =
          switch (reason) {
            case "past" -> "Это уже прошло. Поставить на следующий раз?";
            case "ambiguous_date" ->
                "Сегодня "
                    + clock
                        .instant()
                        .atZone(zone)
                        .format(
                            java.time.format.DateTimeFormatter.ofPattern(
                                "EEEE", Locale.forLanguageTag("ru")))
                    + ". Поставить на сегодня или на следующую?";
            case "missing_day" -> "На какой день поставить запись?";
            case "ambiguous_time" ->
                "В этом часовом поясе время неоднозначно или не существует. Укажите другое время.";
            default -> "На какую дату поставить запись?";
          };
      return clarify(
          session,
          request,
          payload,
          "past".equals(reason)
              ? "confirm"
              : "ambiguous_date".equals(reason) ? "ambiguous_date" : "missing_slot",
          "date",
          question);
    }
    if (resolved.startAt() == null && (type != ItemType.TASK || (time != null && !time.isBlank())))
      return clarify(
          session,
          request,
          payload,
          "missing_slot",
          "time",
          type == ItemType.MEETING
              ? "На какое время поставить встречу?"
              : "На какое время поставить напоминание?");
    Item item = new Item();
    item.setUser(users.getReferenceById(DefaultUser.ID));
    item.setType(type);
    item.setTitle(title);
    item.setDescription(request.text());
    item.setPlace(slot(slots, "place"));
    if (type == ItemType.TASK) item.setDueAt(resolved.dueAt());
    else item.setStartAt(resolved.startAt());
    if (type == ItemType.MEETING && slot(slots, "end_time_expr") != null) {
      var end =
          dates.resolve(
              item.getStartAt().atZone(zone).toLocalDate().toString(),
              slot(slots, "end_time_expr"),
              zone,
              null);
      if (end.startAt() == null || !end.startAt().isAfter(item.getStartAt()))
        return clarify(
            session,
            request,
            payload,
            "missing_slot",
            "end_time",
            "Во сколько закончится встреча? Время должно быть позже начала.");
      item.setEndAt(end.startAt());
    }
    if (!allowConflict && type == ItemType.MEETING) {
      var conflict = conflicts.firstConflict(item, null);
      if (conflict.isPresent()) return conflictClarification(session, request, payload, conflict.get(), zone);
    }
    items.saveAndFlush(item);
    session.setLastItem(item);
    session.setPendingClarification(null);
    return finish(
        session,
        request,
        "done",
        "create_item",
        composer.created(item, zone),
        List.of(item),
        true,
        payload);
  }

  private MessageResponse list(
      ChatSession session, MessageRequest request, JsonNode payload, ZoneId zone) {
    var slots = payload.get("slots");
    String expression = slot(slots, "period_expr");
    if (expression == null) expression = slot(slots, "date_expr");
    DateResolver.Period period;
    try {
      period = dates.period(expression, zone);
    } catch (IllegalArgumentException e) {
      return clarify(
          session,
          request,
          payload,
          "missing_slot",
          "period",
          expression == null
              ? "За какой период показать записи? Например: сегодня, завтра или эта неделя."
              : "Не понял период. Назовите, например: сегодня, завтра, эта неделя или даты"
                  + " «2026-10-24 по 2026-10-30» (не более 62 дней).");
    }
    String filter = slot(slots, "type_filter");
    if (filter != null && !explicitTypeMentioned(request.text(), filter)) filter = null;
    Set<ItemType> types =
        filter == null
            ? EnumSet.allOf(ItemType.class)
            : EnumSet.of(ItemType.valueOf(filter.toUpperCase(Locale.ROOT)));
    var found =
        items.findInPeriod(
            DefaultUser.ID,
            period.from(),
            period.to(),
            types,
            EnumSet.of(ItemStatus.TODO, ItemStatus.DONE));
    if (found.size() == 1) session.setLastItem(found.get(0));
    session.setPendingClarification(null);
    return finish(
        session,
        request,
        "info",
        "list_items",
        composer.list(found, period, zone, filter),
        found,
        false,
        payload);
  }

  private MessageResponse clarify(
      ChatSession session,
      MessageRequest request,
      JsonNode payload,
      String kind,
      String awaiting,
      String reply) {
    session.setPendingClarification(
        mapper.valueToTree(
            Map.of(
                "kind",
                kind,
                "intent",
                payload.get("intent").asText(),
                "draft",
                payload.get("slots"),
                "awaiting",
                List.of(awaiting),
                "created_at",
                clock.instant().toString())));
    return finish(
        session,
        request,
        "clarification",
        payload.get("intent").asText(),
        reply,
        List.of(),
        false,
        payload);
  }

  private boolean pendingExpired(JsonNode pending) {
    try {
      Instant created = Instant.parse(pending.path("created_at").asText());
      return clock.instant().minusSeconds(app.pending().ttlMin() * 60L).isAfter(created);
    } catch (RuntimeException e) {
      return true;
    }
  }

  private List<String> quickReplies(ChatSession session, String result) {
    if (!"clarification".equals(result) || session.getPendingClarification() == null) return List.of();
    return "conflict".equals(session.getPendingClarification().path("kind").asText())
        ? List.of("Выбрать другое время", "Поставить в это же")
        : List.of();
  }

  private MessageResponse conflictClarification(
      ChatSession session, MessageRequest request, JsonNode payload, Item conflict, ZoneId zone) {
    Map<String, Object> pending = new LinkedHashMap<>();
    pending.put("kind", "conflict");
    pending.put("intent", payload.get("intent").asText());
    pending.put("draft", payload.get("slots"));
    pending.put("awaiting", List.of("decision"));
    pending.put("conflict_item_id", conflict.getId());
    pending.put("created_at", clock.instant().toString());
    session.setPendingClarification(mapper.valueToTree(pending));
    return finish(session, request, "clarification", payload.get("intent").asText(),
        composer.conflict(conflict, zone), List.of(), false, payload);
  }

  private MessageResponse clarificationAnswer(
      ChatSession session, MessageRequest request, JsonNode payload, ZoneId zone) {
    JsonNode pending = session.getPendingClarification();
    if (pending == null || pendingExpired(pending)) {
      session.setPendingClarification(null);
      return finish(session, request, "refused", "clarification_answer",
          "Сейчас я не жду уточнения. Повторите команду целиком.", List.of(), false, payload);
    }
    String answer = slot(payload.get("slots"), "answer");
    if (answer == null) return finish(session, request, "clarification", "clarification_answer",
        "Уточните ответ, пожалуйста.", List.of(), false, payload);
    String normalized = answer.toLowerCase(Locale.ROOT).replace('ё', 'е').strip();
    String kind = pending.path("kind").asText();

    if (Set.of("cancel", "no", "нет", "отмена", "не надо").contains(normalized)) {
      session.setPendingClarification(null);
      return finish(session, request, "info", "clarification_answer", "Хорошо, не создаю.",
          List.of(), false, payload);
    }

    if ("confirm_delete".equals(kind)) {
      if (Set.of("yes", "да", "подтверждаю", "удаляй", "удалить").contains(normalized)) {
        JsonNode resumed = resumePayload(pending, "delete_item", "selected_item_id", pending.path("item_id").asText());
        session.setPendingClarification(null);
        return deleteConfirmed(session, request, resumed, zone);
      }
      session.setPendingClarification(null);
      return finish(session, request, "refused", "clarification_answer",
          "Не понял ваш запрос! Повторите, пожалуйста, или сформулируйте иначе.", List.of(), false, payload);
    }

    if ("conflict".equals(kind)) {
      String pendingIntent = pending.path("intent").asText("create_item");
      if (Set.of("keep_conflict", "yes", "все равно", "ставь все равно", "в это же").contains(normalized)) {
        JsonNode resumed = resumePayload(pending, pendingIntent, null, null);
        session.setPendingClarification(null);
        return "update_item".equals(pendingIntent)
            ? update(session, request, resumed, zone, true)
            : create(session, request, resumed, zone, true);
      }
      if ("other_time".equals(normalized) || "выбрать другое".equals(normalized)) {
        var next = pending.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) next).put("kind", "missing_slot");
        ((com.fasterxml.jackson.databind.node.ObjectNode) next).set("awaiting", mapper.valueToTree(List.of("time")));
        ((com.fasterxml.jackson.databind.node.ObjectNode) next).put("created_at", clock.instant().toString());
        session.setPendingClarification(next);
        return finish(session, request, "clarification", "clarification_answer",
            "На какое время поставить встречу?", List.of(), false, payload);
      }
      String timeSlot = "update_item".equals(pendingIntent) ? "new_time_expr" : "time_expr";
      JsonNode resumed = resumePayload(pending, pendingIntent, timeSlot, answer);
      session.setPendingClarification(null);
      return "update_item".equals(pendingIntent)
          ? update(session, request, resumed, zone, false)
          : create(session, request, resumed, zone, false);
    }

    if ("choose_item".equals(kind)) {
      List<UUID> ids = new ArrayList<>();
      pending.path("candidate_ids").forEach(n -> {
        try { ids.add(UUID.fromString(n.asText())); } catch (IllegalArgumentException ignored) { }
      });
      Item selected = null;
      try {
        String digits = normalized.replaceAll("[^0-9]", "");
        int number;
        if (!digits.isEmpty()) number = Integer.parseInt(digits);
        else if (normalized.matches(".*\\b(первая|первую|первый|первое)\\b.*")) number = 1;
        else if (normalized.matches(".*\\b(вторая|вторую|второй|второе)\\b.*")) number = 2;
        else if (normalized.matches(".*\\b(третья|третью|третий|третье)\\b.*")) number = 3;
        else number = -1;
        if (number >= 1 && number <= ids.size()) selected = items.findById(ids.get(number - 1)).orElse(null);
      } catch (NumberFormatException ignored) { }
      if (selected == null) {
        for (UUID id : ids) {
          Item candidate = items.findById(id).orElse(null);
          if (candidate != null && normalized.contains(candidate.getTitle().toLowerCase(Locale.ROOT).replace('ё', 'е'))) {
            selected = candidate; break;
          }
        }
      }
      if (selected == null) return finish(session, request, "clarification", "clarification_answer",
          "Укажите номер записи из списка.", List.of(), false, payload);
      String pendingIntent = pending.path("intent").asText();
      JsonNode resumed = resumePayload(pending, pendingIntent, "selected_item_id", selected.getId().toString());
      session.setPendingClarification(null);
      return switch (Intent.valueOf(pendingIntent)) {
        case search_items -> {
          session.setLastItem(selected);
          yield finish(session, request, "info", "search_items", composer.search(List.of(selected), zone), List.of(selected), false, resumed);
        }
        case update_item -> update(session, request, resumed, zone);
        case complete_item -> complete(session, request, resumed, zone);
        case delete_item -> delete(session, request, resumed, zone);
        default -> finish(session, request, "refused", "clarification_answer",
            "Не получилось продолжить уточнение. Повторите команду целиком.", List.of(), false, payload);
      };
    }

    if ("missing_slot".equals(kind)) {
      String awaiting = pending.path("awaiting").isArray() && !pending.path("awaiting").isEmpty()
          ? pending.path("awaiting").get(0).asText() : "";
      String pendingIntent = pending.path("intent").asText();
      if (("date".equals(awaiting) && (normalized.contains("ту же") || normalized.contains("прежн") || normalized.contains("не меня") || looksLikeTimeOnly(normalized)))
          || ("time".equals(awaiting) && (normalized.contains("то же") || normalized.contains("прежн") || normalized.contains("не меня")))) {
        session.setPendingClarification(null);
        return finish(session, request, "refused", "clarification_answer",
            "Не понял ваш запрос! Повторите, пожалуйста, или сформулируйте иначе.", List.of(), false, payload);
      }
      String slotName = switch (awaiting) {
        case "time" -> "update_item".equals(pendingIntent) ? "new_time_expr" : "time_expr";
        case "date" -> "update_item".equals(pendingIntent) ? "new_date_expr" : "date_expr";
        case "period" -> "period_expr";
        case "item_type" -> "item_type";
        case "title" -> "title";
        case "end_time" -> "end_time_expr";
        default -> awaiting;
      };
      JsonNode resumed = resumePayload(pending, pendingIntent, slotName, answer);
      session.setPendingClarification(null);
      Intent original = Intent.valueOf(resumed.path("intent").asText());
      return switch (original) {
        case create_item -> create(session, request, resumed, zone);
        case list_items -> list(session, request, resumed, zone);
        case search_items -> search(session, request, resumed, zone);
        case update_item -> update(session, request, resumed, zone);
        default -> finish(session, request, "refused", "clarification_answer",
            "Не получилось продолжить уточнение. Повторите команду целиком.", List.of(), false, payload);
      };
    }

    session.setPendingClarification(null);
    return finish(session, request, "refused", "clarification_answer",
        "Не получилось продолжить уточнение. Повторите команду целиком.", List.of(), false, payload);
  }

  private JsonNode resumePayload(JsonNode pending, String intent, String slotName, String value) {
    com.fasterxml.jackson.databind.node.ObjectNode resumed = mapper.createObjectNode();
    resumed.put("intent", intent);
    resumed.put("confidence", 1.0);
    com.fasterxml.jackson.databind.node.ObjectNode slots = pending.path("draft").isObject()
        ? (com.fasterxml.jackson.databind.node.ObjectNode) pending.path("draft").deepCopy()
        : mapper.createObjectNode();
    if (slotName != null && value != null) slots.put(slotName, value);
    resumed.set("slots", slots);
    return resumed;
  }

  private record TargetResolution(Item item, List<Item> candidates, boolean byQuery) {}

  private TargetResolution resolveTarget(ChatSession session, JsonNode slots) {
    String selected = slot(slots, "selected_item_id");
    if (selected != null) {
      try {
        Item item = items.findById(UUID.fromString(selected)).orElse(null);
        if (item != null && item.getDeletedAt() == null && item.getUser().getId().equals(DefaultUser.ID))
          return new TargetResolution(item, List.of(), true);
      } catch (IllegalArgumentException ignored) { }
      return new TargetResolution(null, List.of(), true);
    }
    String ref = slot(slots, "item_ref");
    if ("last".equals(ref)) {
      Item item = session.getLastItem();
      if (item == null || item.getDeletedAt() != null || !item.getUser().getId().equals(DefaultUser.ID))
        return new TargetResolution(null, List.of(), false);
      String requestedType = slot(slots, "item_type");
      if (requestedType != null && item.getType() != ItemType.valueOf(requestedType.toUpperCase(Locale.ROOT)))
        return new TargetResolution(null, List.of(), false);
      return new TargetResolution(item, List.of(), false);
    }
    if ("by_query".equals(ref)) {
      String query = slot(slots, "query");
      if (query == null) return new TargetResolution(null, List.of(), true);
      var ranked = search.search(query, 3);
      String requestedType = slot(slots, "item_type");
      if (requestedType != null) {
        ItemType expected = ItemType.valueOf(requestedType.toUpperCase(Locale.ROOT));
        ranked = ranked.stream().filter(c -> c.item().getType() == expected).toList();
      }
      var confident = search.confident(ranked);
      if (confident.isPresent()) return new TargetResolution(confident.get(), List.of(), true);
      return new TargetResolution(null, ranked.stream().map(ItemSearchService.Candidate::item).toList(), true);
    }
    return new TargetResolution(null, List.of(), false);
  }

  private MessageResponse chooseItemClarification(
      ChatSession session, MessageRequest request, JsonNode payload, List<Item> candidates, ZoneId zone) {
    Map<String, Object> pending = new LinkedHashMap<>();
    pending.put("kind", "choose_item");
    pending.put("intent", payload.path("intent").asText());
    pending.put("draft", payload.path("slots"));
    pending.put("awaiting", List.of("item"));
    pending.put("candidate_ids", candidates.stream().map(Item::getId).toList());
    pending.put("created_at", clock.instant().toString());
    session.setPendingClarification(mapper.valueToTree(pending));
    return finish(session, request, "clarification", payload.path("intent").asText(),
        composer.chooseItem(candidates, zone), candidates, false, payload);
  }

  private MessageResponse search(
      ChatSession session, MessageRequest request, JsonNode payload, ZoneId zone) {
    String query = slot(payload.path("slots"), "query");
    if (query == null || query.isBlank())
      return clarify(session, request, payload, "missing_slot", "query", "Что именно найти?");
    var ranked = search.search(query, 3);
    List<Item> found = ranked.stream().map(ItemSearchService.Candidate::item).toList();
    if (found.isEmpty()) {
      session.setPendingClarification(null);
      return finish(session, request, "info", "search_items", composer.search(List.of(), zone), List.of(), false, payload);
    }
    if (found.size() > 1 && search.confident(ranked).isEmpty())
      return chooseItemClarification(session, request, payload, found, zone);
    session.setPendingClarification(null);
    session.setLastItem(found.get(0));
    return finish(session, request, "info", "search_items", composer.search(found, zone), found, false, payload);
  }

  private MessageResponse update(
      ChatSession session, MessageRequest request, JsonNode payload, ZoneId zone) {
    return update(session, request, payload, zone, false);
  }

  private MessageResponse update(
      ChatSession session, MessageRequest request, JsonNode payload, ZoneId zone, boolean allowConflict) {
    JsonNode slots = payload.get("slots");
    TargetResolution target = resolveTarget(session, slots);
    if (target.item() == null) {
      if (!target.candidates().isEmpty()) return chooseItemClarification(session, request, payload, target.candidates(), zone);
      if (target.byQuery()) return finish(session, request, "info", "update_item",
          "Не нашёл такую запись. Уточните название.", List.of(), false, payload);
      return clarify(session, request, payload, "choose_item", "item", "О какой записи речь? Назовите её.");
    }
    Item item = target.item();
    String newDate = slot(slots, "new_date_expr");
    String newTime = slot(slots, "new_time_expr");
    String newTitle = slot(slots, "new_title");
    if (newTitle != null) {
      item.setTitle(normalizeTitle(newTitle, item.getType()));
      items.saveAndFlush(item);
      session.setLastItem(item);
      session.setPendingClarification(null);
      return finish(session, request, "done", "update_item", composer.renamed(item), List.of(item), true, payload);
    }
    if (newDate == null && newTime == null)
      return clarify(session, request, payload, "missing_slot", "date",
          item.getType() == ItemType.TASK ? "На какую дату перенести задачу?" : "На какую дату или время перенести запись?");

    Instant current = item.getType() == ItemType.TASK ? item.getDueAt() : item.getStartAt();
    if (current == null) return clarify(session, request, payload, "missing_slot", "date", "На какую дату перенести запись?");
    ZonedDateTime old = current.atZone(zone);
    String dateExpr = newDate != null ? newDate : old.toLocalDate().toString();
    String timeExpr = newTime != null ? newTime
        : (item.getType() == ItemType.TASK && old.toLocalTime().equals(LocalTime.of(23, 59)) ? null : old.toLocalTime().toString());
    var resolved = dates.resolve(dateExpr, timeExpr, zone, null);
    if (resolved.needsDateClarification() || (item.getType() != ItemType.TASK && resolved.startAt() == null))
      return clarify(session, request, payload, "missing_slot", resolved.startAt() == null ? "time" : "date",
          resolved.startAt() == null ? "На какое время перенести запись?" : "Уточните новую дату.");

    Instant oldStart = item.getStartAt(), oldEnd = item.getEndAt(), oldDue = item.getDueAt();
    if (item.getType() == ItemType.TASK) item.setDueAt(resolved.dueAt());
    else {
      Duration duration = item.getEndAt() != null && item.getStartAt() != null
          ? Duration.between(item.getStartAt(), item.getEndAt()) : null;
      item.setStartAt(resolved.startAt());
      if (duration != null) item.setEndAt(resolved.startAt().plus(duration));
    }
    if (!allowConflict && item.getType() == ItemType.MEETING) {
      var conflict = conflicts.firstConflict(item, item.getId());
      if (conflict.isPresent()) {
        item.setStartAt(oldStart); item.setEndAt(oldEnd); item.setDueAt(oldDue);
        return conflictClarification(session, request, payload, conflict.get(), zone);
      }
    }
    items.saveAndFlush(item);
    session.setLastItem(item);
    session.setPendingClarification(null);
    return finish(session, request, "done", "update_item", composer.updated(item, zone), List.of(item), true, payload);
  }


  private MessageResponse delete(
      ChatSession session, MessageRequest request, JsonNode payload, ZoneId zone) {
    TargetResolution target = resolveTarget(session, payload.get("slots"));
    if (target.item() == null) {
      if (!target.candidates().isEmpty()) return chooseItemClarification(session, request, payload, target.candidates(), zone);
      if (target.byQuery()) return finish(session, request, "info", "delete_item",
          "Не нашёл такую запись. Уточните название.", List.of(), false, payload);
      return clarify(session, request, payload, "choose_item", "item", "Какую запись удалить?");
    }
    Item item = target.item();
    Map<String, Object> pending = new LinkedHashMap<>();
    pending.put("kind", "confirm_delete");
    pending.put("intent", "delete_item");
    pending.put("draft", payload.path("slots"));
    pending.put("awaiting", List.of("decision"));
    pending.put("item_id", item.getId().toString());
    pending.put("created_at", clock.instant().toString());
    session.setPendingClarification(mapper.valueToTree(pending));
    return finish(session, request, "clarification", "delete_item", composer.deleteConfirmation(item, zone), List.of(item), false, payload);
  }

  private MessageResponse deleteConfirmed(
      ChatSession session, MessageRequest request, JsonNode payload, ZoneId zone) {
    TargetResolution target = resolveTarget(session, payload.get("slots"));
    if (target.item() == null) return finish(session, request, "info", "delete_item",
        "Эта запись уже недоступна.", List.of(), false, payload);
    Item item = target.item();
    item.softDelete();
    items.saveAndFlush(item);
    if (session.getLastItem() != null && session.getLastItem().getId().equals(item.getId())) session.setLastItem(null);
    session.setPendingClarification(null);
    return finish(session, request, "done", "delete_item", composer.deleted(item), List.of(), true, payload);
  }

  private static boolean explicitTypeMentioned(String text, String filter) {
    String t = text == null ? "" : text.toLowerCase(Locale.ROOT).replace('ё', 'е');
    return switch (filter) {
      case "task" -> t.contains("задач");
      case "meeting" -> t.contains("встреч");
      case "reminder" -> t.contains("напомин");
      default -> false;
    };
  }

  private static boolean looksLikeTimeOnly(String text) {
    if (text.matches(".*\\b\\d{1,2}[:.]\\d{2}\\b.*")) return true;
    return (text.contains("утра") || text.contains("вечера") || text.contains("час"))
        && !text.matches(".*(сегодня|завтра|послезавтра|понедель|вторник|сред|четверг|пятниц|суббот|воскрес|январ|феврал|март|апрел|ма[йя]|июн|июл|август|сентябр|октябр|ноябр|декабр).*" );
  }

  private MessageResponse complete(
      ChatSession session, MessageRequest request, JsonNode payload, ZoneId zone) {
    TargetResolution target = resolveTarget(session, payload.get("slots"));
    if (target.item() == null) {
      if (!target.candidates().isEmpty()) return chooseItemClarification(session, request, payload, target.candidates(), zone);
      if (target.byQuery()) return finish(session, request, "info", "complete_item",
          "Не нашёл такую запись. Уточните название.", List.of(), false, payload);
      return clarify(session, request, payload, "choose_item", "item", "О какой записи речь? Назовите её.");
    }
    Item item = target.item();
    item.setStatus(ItemStatus.DONE);
    items.saveAndFlush(item);
    session.setLastItem(item);
    session.setPendingClarification(null);
    return finish(session, request, "done", "complete_item", composer.completed(item), List.of(item), true, payload);
  }

  private MessageResponse finish(
      ChatSession session,
      MessageRequest request,
      String result,
      String intent,
      String reply,
      List<Item> found,
      boolean changed,
      JsonNode payload) {
    Message user = new Message();
    user.setUser(users.getReferenceById(DefaultUser.ID));
    user.setSession(session);
    user.setRole(MessageRole.USER);
    user.setContent(request.text());
    user.setInputType(
        "voice".equals(request.source()) ? MessageInputType.VOICE : MessageInputType.TEXT);
    user.setClientMessageId(request.clientMessageId());
    user.setTimezone(request.timezone());
    messages.save(user);
    Message assistant = new Message();
    assistant.setUser(user.getUser());
    assistant.setSession(session);
    assistant.setRole(MessageRole.ASSISTANT);
    assistant.setContent(reply);
    assistant.setIntent(intent);
    assistant.setIntentPayload(payload);
    assistant.setTimezone(request.timezone());
    if (found.size() == 1) assistant.setItem(found.get(0));
    messages.saveAndFlush(assistant);
    String speech =
        found.size() > 5
            ? "Найдено записей: "
                + found.size()
                + ". Первые пять: "
                + String.join("; ", found.stream().limit(5).map(Item::getTitle).toList())
            : null;
    MessageResponse response =
        new MessageResponse(
            assistant.getId(),
            result,
            intent,
            reply,
            speech,
            found.stream().map(i -> ItemDto.from(i, ZoneId.of(request.timezone()))).toList(),
            changed,
            quickReplies(session, result));
    user.setResponseJson(mapper.valueToTree(response));
    sessions.save(session);
    return response;
  }
}