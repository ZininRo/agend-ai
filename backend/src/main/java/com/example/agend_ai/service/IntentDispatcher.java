package com.example.agend_ai.service;

import com.example.agend_ai.api.dto.ItemDto;
import com.example.agend_ai.api.dto.MessageRequest;
import com.example.agend_ai.api.dto.MessageResponse;
import com.example.agend_ai.api.error.AppException;
import com.example.agend_ai.api.error.ErrorCode;
import com.example.agend_ai.common.DefaultUser;
import com.example.agend_ai.entity.ChatSession;
import com.example.agend_ai.entity.Item;
import com.example.agend_ai.entity.Message;
import com.example.agend_ai.entity.enums.ItemStatus;
import com.example.agend_ai.entity.enums.ItemType;
import com.example.agend_ai.entity.enums.MessageInputType;
import com.example.agend_ai.entity.enums.MessageRole;
import com.example.agend_ai.repository.ChatSessionRepository;
import com.example.agend_ai.repository.ItemRepository;
import com.example.agend_ai.repository.MessageRepository;
import com.example.agend_ai.repository.UserRepository;
import com.example.agend_ai.service.llm.LlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class IntentDispatcher {

    private final LlmClient llmClient;
    private final DateResolver dateResolver;
    private final ResponseComposer responseComposer;
    private final ZoneResolver zoneResolver;

    private final ChatSessionRepository sessionRepository;
    private final ItemRepository itemRepository;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    private static final String SYSTEM_PROMPT = """
            Ты NLU-модуль голосового ассистента. Твоя задача — проанализировать запрос пользователя и вернуть ТОЛЬКО строгий JSON по схеме:
            {
              "intent": "create_item" | "list_items" | "search_items" | "complete_item" | "update_item" | "unknown",
              "confidence": 0.95,
              "slots": {
                "item_type": "task" | "meeting" | "reminder" | null,
                "title": "название задачи/встречи",
                "date_expr": "выражение даты из текста или null",
                "time_expr": "выражение времени из текста или null",
                "type_filter": null
              }
            }
            Интент должен быть строго из белого списка. Никакого дополнительного текста, только валидный JSON.
            """;

    @Transactional
    public MessageResponse process(MessageRequest request) {
        ZoneId zone = zoneResolver.resolve(request.timezone());

        // 1. Найти или создать сессию
        ChatSession session = sessionRepository.findById(request.sessionId()).orElseGet(() -> {
            ChatSession s = new ChatSession();
            s.setId(request.sessionId());
            s.setUser(userRepository.getReferenceById(DefaultUser.ID));
            return sessionRepository.save(s);
        });

        // 2. Вызов LLM для получения структурированного JSON интента
        String llmRawResponse;
        try {
            llmRawResponse = llmClient.generateStructuredJson(SYSTEM_PROMPT, request.text());
        } catch (Exception e) {
            // Если LLM недоступна, возвращаем контролируемую ошибку
            throw new AppException(ErrorCode.LLM_UNAVAILABLE);
        }

        // Парсинг ответа LLM
        JsonNode payload;
        try {
            // Очищаем от возможных Markdown обёрток кода
            String cleanJson = llmRawResponse.replaceAll("```json", "").replaceAll("```", "").trim();
            payload = objectMapper.readTree(cleanJson);
        } catch (Exception e) {
            log.warn("Failed to parse LLM JSON response: {}", llmRawResponse);
            // Возвращаем вежливый отказ по ТЗ (невалидный JSON)
            return saveAndReturnRefused(session, request);
        }

        String intent = payload.path("intent").asText("unknown");
        JsonNode slots = payload.path("slots");

        // 3. Обработка интентов
        if ("create_item".equals(intent)) {
            return handleCreateItem(session, slots, request, zone);
        } else {
            return saveAndReturnRefused(session, request);
        }
    }

    private MessageResponse handleCreateItem(ChatSession session, JsonNode slots, MessageRequest request, ZoneId zone) {
        String typeStr = slots.path("item_type").asText("task").toUpperCase();
        ItemType type;
        try {
            type = ItemType.valueOf(typeStr);
        } catch (Exception e) {
            type = ItemType.TASK;
        }

        String title = slots.path("title").asText(request.text());
        String dateExpr = slots.path("date_expr").asText(null);
        String timeExpr = slots.path("time_expr").asText(null);

        // Резолвим дату и время через детерминированный модуль
        DateResolver.ResolvedDateTime resolved = dateResolver.resolve(dateExpr, timeExpr, zone, null);

        Item item = new Item();
        item.setUser(userRepository.getReferenceById(DefaultUser.ID));
        item.setType(type);
        item.setTitle(title);
        item.setDescription(request.text());
        item.setStartAt(resolved.startAt());
        item.setDueAt(resolved.dueAt());
        item.setStatus(ItemStatus.TODO);
        item = itemRepository.save(item);

        // Обновляем last_item в сессии
        session.setLastItem(item);
        sessionRepository.save(session);

        String replyText = switch (type) {
            case MEETING -> responseComposer.composeCreateMeeting(item, dateExpr != null ? dateExpr : "завтра");
            case REMINDER -> responseComposer.composeCreateReminder(item, dateExpr != null ? dateExpr : "завтра");
            case TASK -> responseComposer.composeCreateTask(item, dateExpr != null ? dateExpr : "скоро");
        };

        // Сохраняем реплики в историю
        saveMessages(session, request, replyText, "create_item", item);

        ItemDto itemDto = ItemDto.from(item, zone);
        return new MessageResponse(
                UUID.randomUUID(),
                "done",
                "create_item",
                replyText,
                null,
                List.of(itemDto),
                true,
                List.of()
        );
    }

    private MessageResponse saveAndReturnRefused(ChatSession session, MessageRequest request) {
        String replyText = responseComposer.composeRefused();
        saveMessages(session, request, replyText, "unknown", null);
        return new MessageResponse(
                UUID.randomUUID(),
                "refused",
                "unknown",
                replyText,
                null,
                List.of(),
                false,
                List.of()
        );
    }

    private void saveMessages(ChatSession session, MessageRequest request, String assistantReply, String intent, Item item) {
        Message userMsg = new Message();
        userMsg.setUser(userRepository.getReferenceById(DefaultUser.ID));
        userMsg.setSession(session);
        userMsg.setRole(MessageRole.USER);
        userMsg.setContent(request.text());
        userMsg.setInputType("voice".equals(request.source()) ? MessageInputType.VOICE : MessageInputType.TEXT);
        userMsg.setClientMessageId(request.clientMessageId());
        userMsg.setTimezone(request.timezone() != null ? request.timezone() : "Europe/Moscow");
        messageRepository.save(userMsg);

        Message assistantMsg = new Message();
        assistantMsg.setUser(userRepository.getReferenceById(DefaultUser.ID));
        assistantMsg.setSession(session);
        assistantMsg.setRole(MessageRole.ASSISTANT);
        assistantMsg.setContent(assistantReply);
        assistantMsg.setIntent(intent);
        assistantMsg.setItem(item);
        assistantMsg.setTimezone(request.timezone() != null ? request.timezone() : "Europe/Moscow");
        messageRepository.save(assistantMsg);
    }
}