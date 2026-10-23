package com.example.agend_ai.api.dto;

import java.util.List;
import java.util.UUID;

public record MessageResponse(
        UUID messageId,
        String result,
        String intent,
        String replyText,
        String speechText,
        List<ItemDto> items,
        boolean dataChanged,
        List<String> quickReplies
) {}
