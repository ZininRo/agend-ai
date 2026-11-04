package com.example.agend_ai.calendar;

import com.example.agend_ai.api.error.AppException;
import com.example.agend_ai.api.error.ErrorCode;
import com.example.agend_ai.calendar.dto.CalendarSyncResponse;
import com.example.agend_ai.calendar.google.GoogleApiException;
import com.example.agend_ai.config.GoogleCalendarProperties;
import com.example.agend_ai.entity.CalendarConnection;
import com.example.agend_ai.entity.CalendarSyncOutbox;
import com.example.agend_ai.entity.ExternalCalendar;
import com.example.agend_ai.entity.Item;
import com.example.agend_ai.entity.ItemCalendarLink;
import com.example.agend_ai.entity.enums.ItemSource;
import com.example.agend_ai.entity.enums.ItemStatus;
import com.example.agend_ai.entity.enums.ItemType;
import com.example.agend_ai.repository.CalendarConnectionRepository;
import com.example.agend_ai.repository.CalendarSyncOutboxRepository;
import com.example.agend_ai.repository.ExternalCalendarRepository;
import com.example.agend_ai.repository.ItemCalendarLinkRepository;
import com.example.agend_ai.repository.ItemRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.zone.ZoneRulesException;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CalendarSyncWorker {
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String GOOGLE = "GOOGLE";

  private final CalendarProvider provider;
  private final GoogleCalendarProperties properties;
  private final CalendarCredentialService credentials;
  private final CalendarConnectionRepository connections;
  private final ExternalCalendarRepository calendars;
  private final ItemCalendarLinkRepository links;
  private final CalendarSyncOutboxRepository outbox;
  private final ItemRepository items;
  private final Clock clock;

  @Transactional
  public CalendarSyncResponse syncUser(UUID userId) {
    CalendarConnection connection =
        connections
            .findByUser_IdAndProvider(userId, GOOGLE)
            .filter(c -> "CONNECTED".equals(c.getStatus()) && c.isSyncEnabled())
            .orElseThrow(() -> new AppException(ErrorCode.CALENDAR_REAUTH_REQUIRED));
    int calendarCount = 0;
    int eventCount = 0;
    for (ExternalCalendar calendar :
        calendars.findAllByConnection_IdAndSyncEnabledTrueAndAvailableTrue(connection.getId())) {
      ExternalCalendar locked = calendars.findByIdForUpdate(calendar.getId()).orElse(null);
      if (locked == null || !locked.isAvailable() || !locked.isSyncEnabled()) continue;
      eventCount += syncCalendarInternal(locked);
      calendarCount++;
    }
    int outbound = processOutboxInternal(userId, 100);
    return new CalendarSyncResponse(calendarCount, eventCount, outbound);
  }

  @Transactional
  public int syncCalendar(UUID calendarId) {
    ExternalCalendar calendar = calendars.findByIdForUpdate(calendarId).orElse(null);
    if (calendar == null || !calendar.isAvailable() || !calendar.isSyncEnabled()) return 0;
    CalendarConnection connection = calendar.getConnection();
    if (!connection.isSyncEnabled() || !"CONNECTED".equals(connection.getStatus())) return 0;
    return syncCalendarInternal(calendar);
  }

  @Transactional
  public int processOutboxBatch(int limit) {
    return processOutboxInternal(null, limit);
  }

  @Transactional
  public void requestSyncFromWebhook(
      String channelId, String resourceId, String channelToken) {
    if (channelId == null || channelId.isBlank() || channelToken == null || channelToken.isBlank()) return;
    ExternalCalendar calendar = calendars.findByWatchChannelId(channelId).orElse(null);
    if (calendar == null || calendar.getWatchTokenHash() == null) return;
    if (!CalendarHash.sha256(channelToken).equals(calendar.getWatchTokenHash())) return;
    if (calendar.getWatchResourceId() != null
        && resourceId != null
        && !calendar.getWatchResourceId().equals(resourceId)) return;
    if (!calendar.isAvailable() || !calendar.isSyncEnabled()) return;
    if (!"CONNECTED".equals(calendar.getConnection().getStatus())) return;
    calendar.setSyncRequestedAt(clock.instant());
    calendars.save(calendar);
  }

  private int syncCalendarInternal(ExternalCalendar calendar) {
    CalendarConnection connection = calendar.getConnection();
    String token = credentials.accessToken(connection);
    Instant now = clock.instant();
    boolean full =
        calendar.getSyncToken() == null
            || calendar.getLastFullSyncAt() == null
            || calendar.getLastFullSyncAt()
                .plus(Duration.ofHours(properties.fullSyncEveryHours()))
                .isBefore(now);
    Instant timeMin = full ? now.minus(Duration.ofDays(properties.initialPastDays())) : null;
    Instant timeMax = full ? now.plus(Duration.ofDays(properties.initialFutureDays())) : null;
    Set<String> seen = new HashSet<>();

    try {
      SyncPass pass = fetchAndApply(calendar, token, full ? null : calendar.getSyncToken(), timeMin, timeMax, seen);
      if (pass.gone()) {
        full = true;
        timeMin = now.minus(Duration.ofDays(properties.initialPastDays()));
        timeMax = now.plus(Duration.ofDays(properties.initialFutureDays()));
        seen = new HashSet<>();
        pass = fetchAndApply(calendar, token, null, timeMin, timeMax, seen);
        if (pass.gone()) throw new GoogleApiException(410);
      }
      calendar.setSyncToken(pass.nextSyncToken());
      calendar.setLastSyncAt(now);
      calendar.setSyncRequestedAt(null);
      if (full) {
        calendar.setLastFullSyncAt(now);
        removeMissingFromFullSync(calendar, seen, timeMin, timeMax);
      }
      calendars.save(calendar);
      connection.setLastSyncAt(now);
      connection.setLastError(null);
      connections.save(connection);
      ensureWatch(calendar, token, now);
      return pass.applied();
    } catch (GoogleApiException e) {
      if (e.status() == 401 || e.status() == 403) {
        credentials.markReauth(connection, "calendar_sync_http_" + e.status());
        throw new AppException(ErrorCode.CALENDAR_REAUTH_REQUIRED);
      }
      connection.setLastError("calendar_sync_http_" + e.status());
      connections.save(connection);
      if (e.status() == 410) {
        calendar.setSyncToken(null);
        calendar.setLastFullSyncAt(null);
        calendar.setSyncRequestedAt(now);
        calendars.save(calendar);
      }
      throw new AppException(ErrorCode.CALENDAR_UNAVAILABLE);
    }
  }

  private SyncPass fetchAndApply(
      ExternalCalendar calendar,
      String accessToken,
      String syncToken,
      Instant timeMin,
      Instant timeMax,
      Set<String> seen) {
    String page = null;
    String nextSync = null;
    int applied = 0;
    do {
      final RemoteEventPage response;
      try {
        response =
            provider.listEvents(
                accessToken,
                calendar.getProviderCalendarId(),
                syncToken,
                page,
                timeMin,
                timeMax);
      } catch (GoogleApiException e) {
        if (e.status() == 410) return new SyncPass(applied, null, true);
        throw e;
      }
      for (RemoteEvent event : response.events()) {
        seen.add(CalendarHash.sha256(event.id()));
        if (applyInbound(calendar, event)) applied++;
      }
      page = response.nextPageToken();
      if (response.nextSyncToken() != null) nextSync = response.nextSyncToken();
    } while (page != null);
    return new SyncPass(applied, nextSync, false);
  }

  private boolean applyInbound(ExternalCalendar calendar, RemoteEvent event) {
    String eventHash = CalendarHash.sha256(event.id());
    ItemCalendarLink link =
        links.findByExternalCalendar_IdAndExternalEventIdHash(calendar.getId(), eventHash).orElse(null);

    if (link == null && event.agendaiItemId() != null) {
      try {
        UUID itemId = UUID.fromString(event.agendaiItemId());
        Item local =
            items.findActiveByIdAndUserId(itemId, calendar.getConnection().getUser().getId()).orElse(null);
        if (local != null) link = createLink(local, calendar, event);
      } catch (IllegalArgumentException ignored) {
        // Foreign/invalid private metadata is ignored.
      }
    }

    if (event.cancelled()) {
      if (link == null) return false;
      if (outbox.existsByItemId(link.getItemId())) return false; // local pending change wins
      Item item =
          items
              .findActiveByIdAndUserId(link.getItemId(), calendar.getConnection().getUser().getId())
              .orElse(null);
      if (item != null) {
        item.softDelete();
        items.save(item);
      }
      updateLinkMetadata(link, event);
      return item != null;
    }

    if (!validEventTime(event)) return false;
    if (link == null) {
      Item item = new Item();
      item.setUser(calendar.getConnection().getUser());
      item.setType(ItemType.MEETING);
      item.setSource(ItemSource.GOOGLE_CALENDAR);
      applyEventFields(item, calendar, event);
      items.saveAndFlush(item);
      createLink(item, calendar, event);
      return true;
    }

    if (outbox.existsByItemId(link.getItemId())) return false; // do not overwrite unsent local edits
    Item item =
        items
            .findActiveByIdAndUserId(link.getItemId(), calendar.getConnection().getUser().getId())
            .orElse(null);
    if (item == null) {
      updateLinkMetadata(link, event);
      return false;
    }
    applyEventFields(item, calendar, event);
    items.save(item);
    updateLinkMetadata(link, event);
    return true;
  }

  private void applyEventFields(Item item, ExternalCalendar calendar, RemoteEvent event) {
    item.setTitle(trim(event.summary(), 255, "Без названия"));
    item.setDescription(blankToNull(event.description()));
    item.setPlace(trim(event.location(), 255, null));
    item.setStatus(ItemStatus.TODO);
    ZoneId zone = eventZone(event, calendar, item.getUser().getTimezone());
    if (event.allDay()) {
      LocalDate startDate = event.allDayStart();
      LocalDate endDate = event.allDayEndExclusive() != null ? event.allDayEndExclusive() : startDate.plusDays(1);
      item.setAllDay(true);
      item.setStartAt(startDate.atStartOfDay(zone).toInstant());
      item.setEndAt(endDate.atStartOfDay(zone).toInstant());
    } else {
      item.setAllDay(false);
      item.setStartAt(event.startAt());
      item.setEndAt(
          event.endAt() != null
              ? event.endAt()
              : event.startAt().plus(defaultDuration(item.getType())));
    }
    item.setDueAt(null);
  }

  private void removeMissingFromFullSync(
      ExternalCalendar calendar, Set<String> seen, Instant timeMin, Instant timeMax) {
    UUID userId = calendar.getConnection().getUser().getId();
    for (ItemCalendarLink link : links.findAllByExternalCalendar_Id(calendar.getId())) {
      if (seen.contains(link.getExternalEventIdHash()) || outbox.existsByItemId(link.getItemId())) continue;
      Item item = items.findActiveByIdAndUserId(link.getItemId(), userId).orElse(null);
      if (item == null || item.getStartAt() == null) continue;
      if (!item.getStartAt().isBefore(timeMin) && item.getStartAt().isBefore(timeMax)) {
        item.softDelete();
        items.save(item);
      }
    }
  }

  private int processOutboxInternal(UUID onlyUserId, int limit) {
    int processed = 0;
    List<CalendarSyncOutbox> ready = outbox.findReady(clock.instant());
    for (CalendarSyncOutbox row : ready) {
      if (processed >= limit) break;
      if (onlyUserId != null && !onlyUserId.equals(row.getUser().getId())) continue;
      try {
        processOutboxRow(row);
        processed++;
      } catch (AppException e) {
        retry(row, e.getCode().name());
      } catch (GoogleApiException e) {
        retry(row, "google_http_" + e.status());
      } catch (RuntimeException e) {
        retry(row, e.getClass().getSimpleName());
      }
    }
    return processed;
  }

  private void processOutboxRow(CalendarSyncOutbox row) {
    UUID userId = row.getUser().getId();
    CalendarConnection connection =
        connections.findByUser_IdAndProvider(userId, GOOGLE).orElse(null);
    if (connection == null || !connection.isSyncEnabled() || !"CONNECTED".equals(connection.getStatus())) {
      outbox.delete(row);
      return;
    }
    ItemCalendarLink link = links.findByItem_Id(row.getItemId()).orElse(null);
    if ("DELETE".equals(row.getOperation())) {
      deleteRemote(row, connection, link);
      return;
    }

    Item item = items.findActiveByIdAndUserId(row.getItemId(), userId).orElse(null);
    if (item == null || item.getDeletedAt() != null || item.getStatus() == ItemStatus.CANCELLED) {
      row.setOperation("DELETE");
      outbox.save(row);
      deleteRemote(row, connection, link);
      return;
    }
    if (!eligible(item)) {
      outbox.delete(row);
      return;
    }

    ExternalCalendar target;
    if (link != null) {
      target = link.getExternalCalendar();
      if (!target.isAvailable() || !target.isSyncEnabled() || !target.writable()) {
        outbox.delete(row); // a disabled/read-only imported calendar becomes local-only
        return;
      }
    } else {
      target =
          calendars
              .findByConnection_IdAndWriteTargetTrueAndAvailableTrue(connection.getId())
              .filter(ExternalCalendar::writable)
              .orElse(null);
      if (target == null) {
        outbox.delete(row);
        return;
      }
    }

    String accessToken = credentials.accessToken(connection);
    RemoteEventDraft draft = draft(item, target);
    try {
      RemoteEvent remote;
      if (link == null) {
        remote = provider.createEvent(accessToken, target.getProviderCalendarId(), draft);
        link = createLink(item, target, remote);
      } else {
        remote =
            provider.updateEvent(
                accessToken,
                target.getProviderCalendarId(),
                link.getExternalEventId(),
                draft,
                link.getExternalEtag());
        updateLinkMetadata(link, remote);
      }
      outbox.delete(row);
    } catch (GoogleApiException e) {
      if (e.status() == 404 && link != null) {
        RemoteEvent remote = provider.createEvent(accessToken, target.getProviderCalendarId(), draft);
        link.setExternalEventId(remote.id());
        link.setExternalEventIdHash(CalendarHash.sha256(remote.id()));
        updateLinkMetadata(link, remote);
        outbox.delete(row);
        return;
      }
      if (e.status() == 412 && link != null) {
        target.setSyncRequestedAt(clock.instant());
        calendars.save(target);
      }
      if (e.status() == 401 || e.status() == 403) {
        credentials.markReauth(connection, "calendar_outbox_http_" + e.status());
        throw new AppException(ErrorCode.CALENDAR_REAUTH_REQUIRED);
      }
      throw e;
    }
  }

  private void deleteRemote(
      CalendarSyncOutbox row, CalendarConnection connection, ItemCalendarLink link) {
    if (link == null) {
      outbox.delete(row);
      return;
    }
    ExternalCalendar target = link.getExternalCalendar();
    if (!target.isAvailable() || !target.writable()) {
      links.delete(link);
      outbox.delete(row);
      return;
    }
    String accessToken = credentials.accessToken(connection);
    try {
      provider.deleteEvent(accessToken, target.getProviderCalendarId(), link.getExternalEventId());
      links.delete(link);
      outbox.delete(row);
    } catch (GoogleApiException e) {
      if (e.status() == 401 || e.status() == 403) {
        credentials.markReauth(connection, "calendar_delete_http_" + e.status());
        throw new AppException(ErrorCode.CALENDAR_REAUTH_REQUIRED);
      }
      throw e;
    }
  }

  private RemoteEventDraft draft(Item item, ExternalCalendar calendar) {
    ZoneId zone = safeZone(calendar.getTimezone(), item.getUser().getTimezone());
    if (item.isAllDay()) {
      LocalDate start = item.getStartAt().atZone(zone).toLocalDate();
      LocalDate end =
          item.getEndAt() == null
              ? start.plusDays(1)
              : item.getEndAt().atZone(zone).toLocalDate();
      if (!end.isAfter(start)) end = start.plusDays(1);
      return new RemoteEventDraft(
          item.getTitle(), item.getDescription(), item.getPlace(), null, null, start, end, zone.getId(), item.getId().toString());
    }
    Instant end =
        item.getEndAt() != null
            ? item.getEndAt()
            : item.getStartAt().plus(defaultDuration(item.getType()));
    return new RemoteEventDraft(
        item.getTitle(),
        item.getDescription(),
        item.getPlace(),
        item.getStartAt(),
        end,
        null,
        null,
        zone.getId(),
        item.getId().toString());
  }

  private ItemCalendarLink createLink(Item item, ExternalCalendar calendar, RemoteEvent event) {
    ItemCalendarLink link = new ItemCalendarLink();
    link.setItem(item);
    link.setExternalCalendar(calendar);
    link.setExternalEventId(event.id());
    link.setExternalEventIdHash(CalendarHash.sha256(event.id()));
    updateLinkMetadata(link, event);
    return links.save(link);
  }

  private void updateLinkMetadata(ItemCalendarLink link, RemoteEvent event) {
    link.setExternalEtag(event.etag());
    link.setExternalUpdatedAt(event.updatedAt());
    link.setLastSyncedAt(clock.instant());
    links.save(link);
  }

  private void ensureWatch(ExternalCalendar calendar, String accessToken, Instant now) {
    String base = properties.webhookBaseUrl();
    if (base == null || base.isBlank()) return;
    if (calendar.getWatchExpiresAt() != null
        && calendar.getWatchExpiresAt().isAfter(now.plus(Duration.ofHours(24)))) return;
    if (calendar.getWatchChannelId() != null && calendar.getWatchResourceId() != null) {
      provider.stopWatch(accessToken, calendar.getWatchChannelId(), calendar.getWatchResourceId());
    }
    String channelId = UUID.randomUUID().toString();
    String rawToken = randomToken();
    String callback = stripSlash(base) + "/api/calendar/google/webhook";
    WatchRegistration watch =
        provider.watchEvents(
            accessToken, calendar.getProviderCalendarId(), channelId, callback, rawToken);
    calendar.setWatchChannelId(watch.channelId());
    calendar.setWatchResourceId(watch.resourceId());
    calendar.setWatchTokenHash(CalendarHash.sha256(rawToken));
    calendar.setWatchExpiresAt(watch.expiration());
    calendars.save(calendar);
  }

  private void retry(CalendarSyncOutbox row, String error) {
    int attempts = row.getAttempts() + 1;
    row.setAttempts(attempts);
    row.setLastError(trim(error, 255, "sync_failed"));
    long seconds = attempts >= 6 ? 3600 : Math.min(900, 15L * (1L << Math.min(attempts - 1, 5)));
    row.setAvailableAt(clock.instant().plusSeconds(seconds));
    outbox.save(row);
  }

  private static boolean eligible(Item item) {
    return (item.getType() == ItemType.MEETING || item.getType() == ItemType.REMINDER)
        && item.getStartAt() != null;
  }

  private static boolean validEventTime(RemoteEvent event) {
    return event.allDay() ? event.allDayStart() != null : event.startAt() != null;
  }

  private static Duration defaultDuration(ItemType type) {
    return type == ItemType.REMINDER ? Duration.ofMinutes(5) : Duration.ofHours(1);
  }

  private static ZoneId eventZone(RemoteEvent event, ExternalCalendar calendar, String userZone) {
    return safeZone(event.timezone(), calendar.getTimezone(), userZone);
  }

  private static ZoneId safeZone(String... candidates) {
    for (String value : candidates) {
      if (value == null || value.isBlank()) continue;
      try {
        return ZoneId.of(value);
      } catch (ZoneRulesException ignored) {
      }
    }
    return ZoneId.of("UTC");
  }

  private static String trim(String value, int max, String fallback) {
    if (value == null || value.isBlank()) return fallback;
    String stripped = value.strip();
    return stripped.length() <= max ? stripped : stripped.substring(0, max);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static String stripSlash(String value) {
    String out = value.strip();
    while (out.endsWith("/")) out = out.substring(0, out.length() - 1);
    return out;
  }

  private static String randomToken() {
    byte[] bytes = new byte[24];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private record SyncPass(int applied, String nextSyncToken, boolean gone) {}
}
