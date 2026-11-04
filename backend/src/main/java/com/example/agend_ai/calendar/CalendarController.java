package com.example.agend_ai.calendar;

import com.example.agend_ai.calendar.dto.CalendarConnectResponse;
import com.example.agend_ai.calendar.dto.CalendarSelectionRequest;
import com.example.agend_ai.calendar.dto.CalendarStatusDto;
import com.example.agend_ai.calendar.dto.CalendarSyncResponse;
import com.example.agend_ai.security.CurrentUser;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/calendar")
@RequiredArgsConstructor
public class CalendarController {
  private final CalendarConnectionService connections;
  private final CalendarSyncWorker sync;
  private final CurrentUser currentUser;

  @GetMapping("/status")
  public CalendarStatusDto status() {
    return connections.status();
  }

  @PostMapping("/google/connect")
  public CalendarConnectResponse connectGoogle() {
    return connections.connectUrl();
  }

  @GetMapping("/google/callback")
  public ResponseEntity<Void> googleCallback(
      @RequestParam(required = false) String code,
      @RequestParam(required = false) String state,
      @RequestParam(required = false) String error) {
    String outcome = "connected";
    if (error != null && !error.isBlank()) {
      outcome = "denied";
    } else {
      try {
        connections.completeOAuth(code, state);
      } catch (RuntimeException ex) {
        outcome = "error";
      }
    }
    return ResponseEntity.status(HttpStatus.FOUND)
        .location(URI.create(connections.callbackRedirect(outcome)))
        .build();
  }

  @PostMapping("/google/disconnect")
  public ResponseEntity<Void> disconnectGoogle() {
    connections.disconnect();
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/refresh-calendars")
  public CalendarStatusDto refreshCalendars() {
    return connections.refreshCalendarsForCurrentUser();
  }

  @PatchMapping("/calendars/{calendarId}")
  public CalendarStatusDto updateCalendar(
      @PathVariable UUID calendarId, @RequestBody CalendarSelectionRequest request) {
    return connections.updateCalendar(calendarId, request);
  }

  @PostMapping("/sync")
  public CalendarSyncResponse syncNow() {
    connections.refreshCalendarsForCurrentUser();
    return sync.syncUser(currentUser.id());
  }

  /** Google push notifications contain no event body; they only signal that incremental sync is needed. */
  @PostMapping("/google/webhook")
  public ResponseEntity<Void> googleWebhook(
      @RequestHeader(value = "X-Goog-Channel-ID", required = false) String channelId,
      @RequestHeader(value = "X-Goog-Resource-ID", required = false) String resourceId,
      @RequestHeader(value = "X-Goog-Channel-Token", required = false) String channelToken,
      @RequestHeader(value = "X-Goog-Resource-State", required = false) String resourceState) {
    if (resourceState != null && !"not_exists".equalsIgnoreCase(resourceState)) {
      sync.requestSyncFromWebhook(channelId, resourceId, channelToken);
    }
    return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
  }
}
