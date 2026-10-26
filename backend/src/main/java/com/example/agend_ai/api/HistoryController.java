package com.example.agend_ai.api;

import com.example.agend_ai.api.dto.*;
import com.example.agend_ai.api.error.*;
import com.example.agend_ai.repository.MessageRepository;
import com.example.agend_ai.service.ZoneResolver;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/history")
@RequiredArgsConstructor
public class HistoryController {
  private final MessageRepository messages;
  private final ZoneResolver zones;

  @GetMapping
  public HistoryResponse history(
      @RequestParam("session_id") UUID sessionId,
      @RequestParam(value = "limit", defaultValue = "50") int limit) {
    if (sessionId == null || sessionId.version() != 4 || sessionId.variant() != 2 || limit < 1 || limit > 200)
      throw new AppException(ErrorCode.REQUEST_INVALID);
    var rows = new ArrayList<>(messages.history(sessionId, PageRequest.of(0, limit)));
    Collections.reverse(rows);
    var zone = zones.resolve(null);
    return new HistoryResponse(rows.stream().map(m -> HistoryMessageDto.from(m, zone)).toList());
  }
}
