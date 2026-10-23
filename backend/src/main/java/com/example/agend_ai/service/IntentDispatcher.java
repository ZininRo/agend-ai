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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IntentDispatcher {
  private final LlmClient llmClient;
  private final IntentValidator validator;
  private final PromptBuilder prompts;
  private final DateResolver dates;
  private final ResponseComposer composer;
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
          "(?iu)(игнорируй|забудь|ignore|disregard).{0,60}(инструкц|правил|prompt|instruction)|(?:system|assistant)\\s*:|раскрой.{0,30}(промпт|ключ|секрет)");

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
        payload = validator.parse(generated);
        break;
      } catch (IllegalArgumentException ignored) {
        /* no raw provider output in logs */
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
      case create_item -> create(session, request, payload, zone);
      case list_items -> list(session, request, payload, zone);
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
      default ->
          finish(
              session,
              request,
              "refused",
              intent.name(),
              composer.composeRefused(),
              List.of(),
              false,
              payload);
    };
  }

  private static String slot(JsonNode slots, String name) {
    JsonNode n = slots.get(name);
    return n == null || n.isNull() ? null : n.asText().strip();
  }

  private MessageResponse create(
      ChatSession session, MessageRequest request, JsonNode payload, ZoneId zone) {
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
    String date = slot(slots, "date_expr"), time = slot(slots, "time_expr");
    if (type == ItemType.TASK && slot(slots, "deadline_expr") != null)
      date = slot(slots, "deadline_expr");
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
          "За какой период показать записи? Укажите не более 62 дней.");
    }
    String filter = slot(slots, "type_filter");
    Set<ItemType> types =
        filter == null
            ? EnumSet.allOf(ItemType.class)
            : EnumSet.of(ItemType.valueOf(filter.toUpperCase(Locale.ROOT)));
    var found =
        items.findInPeriod(
            DefaultUser.ID, period.from(), period.to(), types, EnumSet.allOf(ItemStatus.class));
    if (found.size() == 1) session.setLastItem(found.get(0));
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
            List.of());
    user.setResponseJson(mapper.valueToTree(response));
    sessions.save(session);
    return response;
  }
}
