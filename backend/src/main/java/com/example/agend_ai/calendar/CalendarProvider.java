package com.example.agend_ai.calendar;

import java.time.Instant;
import java.util.List;

/** Provider boundary: P4 ships Google; a CalDAV/Yandex adapter can implement the same contract later. */
public interface CalendarProvider {
  String providerId();

  boolean configured();

  String authorizationUrl(String state);

  OAuthGrant exchangeCode(String code);

  OAuthGrant refreshAccessToken(String refreshToken);

  List<RemoteCalendar> listCalendars(String accessToken);

  RemoteEventPage listEvents(
      String accessToken,
      String calendarId,
      String syncToken,
      String pageToken,
      Instant timeMin,
      Instant timeMax);

  RemoteEvent createEvent(String accessToken, String calendarId, RemoteEventDraft draft);

  RemoteEvent updateEvent(
      String accessToken, String calendarId, String eventId, RemoteEventDraft draft, String etag);

  void deleteEvent(String accessToken, String calendarId, String eventId);

  WatchRegistration watchEvents(
      String accessToken, String calendarId, String channelId, String callbackUrl, String channelToken);

  void stopWatch(String accessToken, String channelId, String resourceId);

  void revokeToken(String token);
}
