package com.example.agend_ai.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.example.agend_ai.entity.CalendarConnection;
import com.example.agend_ai.entity.CalendarSyncOutbox;
import com.example.agend_ai.entity.ExternalCalendar;
import com.example.agend_ai.entity.Item;
import com.example.agend_ai.entity.ItemCalendarLink;
import com.example.agend_ai.entity.User;
import com.example.agend_ai.entity.enums.ItemType;
import com.example.agend_ai.repository.CalendarConnectionRepository;
import com.example.agend_ai.repository.CalendarSyncOutboxRepository;
import com.example.agend_ai.repository.ExternalCalendarRepository;
import com.example.agend_ai.repository.ItemCalendarLinkRepository;
import com.example.agend_ai.repository.ItemRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CalendarOutboxServiceTest {
  private CalendarConnectionRepository connections;
  private ExternalCalendarRepository calendars;
  private CalendarSyncOutboxRepository outbox;
  private ItemRepository items;
  private ItemCalendarLinkRepository links;
  private CalendarOutboxService service;

  @BeforeEach
  void setUp() {
    connections = mock(CalendarConnectionRepository.class);
    calendars = mock(ExternalCalendarRepository.class);
    outbox = mock(CalendarSyncOutboxRepository.class);
    items = mock(ItemRepository.class);
    links = mock(ItemCalendarLinkRepository.class);
    service =
        new CalendarOutboxService(
            connections,
            calendars,
            outbox,
            items,
            links,
            Clock.fixed(Instant.parse("2026-11-04T12:00:00Z"), ZoneOffset.UTC));
  }

  @Test
  void newLocalMeetingRequiresWriteTargetAndQueuesUpsert() {
    Item item = meeting();
    CalendarConnection connection = connected(item.getUser());
    ExternalCalendar target = new ExternalCalendar();
    target.setId(UUID.randomUUID());
    when(connections.findByUser_IdAndProvider(item.getUser().getId(), "GOOGLE"))
        .thenReturn(Optional.of(connection));
    when(links.findByItem_Id(item.getId())).thenReturn(Optional.empty());
    when(calendars.findByConnection_IdAndWriteTargetTrueAndAvailableTrue(connection.getId()))
        .thenReturn(Optional.of(target));
    when(outbox.findById(item.getId())).thenReturn(Optional.empty());

    service.enqueue(item);

    ArgumentCaptor<CalendarSyncOutbox> saved = ArgumentCaptor.forClass(CalendarSyncOutbox.class);
    verify(outbox).save(saved.capture());
    assertThat(saved.getValue().getOperation()).isEqualTo("UPSERT");
    assertThat(saved.getValue().getItemId()).isEqualTo(item.getId());
    assertThat(saved.getValue().getAvailableAt()).isEqualTo(Instant.parse("2026-11-04T12:00:00Z"));
  }

  @Test
  void existingGoogleLinkDoesNotDependOnGlobalWriteTarget() {
    Item item = meeting();
    CalendarConnection connection = connected(item.getUser());
    ItemCalendarLink link = new ItemCalendarLink();
    when(connections.findByUser_IdAndProvider(item.getUser().getId(), "GOOGLE"))
        .thenReturn(Optional.of(connection));
    when(links.findByItem_Id(item.getId())).thenReturn(Optional.of(link));
    when(outbox.findById(item.getId())).thenReturn(Optional.empty());

    service.enqueue(item);

    verify(outbox).save(any(CalendarSyncOutbox.class));
    verify(calendars, never()).findByConnection_IdAndWriteTargetTrueAndAvailableTrue(any());
  }

  private static Item meeting() {
    User user = new User();
    user.setId(UUID.randomUUID());
    Item item = new Item();
    item.setId(UUID.randomUUID());
    item.setUser(user);
    item.setType(ItemType.MEETING);
    item.setTitle("Встреча");
    item.setStartAt(Instant.parse("2026-11-05T10:00:00Z"));
    return item;
  }

  private static CalendarConnection connected(User user) {
    CalendarConnection connection = new CalendarConnection();
    connection.setId(UUID.randomUUID());
    connection.setUser(user);
    connection.setProvider("GOOGLE");
    connection.setStatus("CONNECTED");
    connection.setSyncEnabled(true);
    return connection;
  }
}
