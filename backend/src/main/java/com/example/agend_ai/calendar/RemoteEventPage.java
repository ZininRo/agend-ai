package com.example.agend_ai.calendar;

import java.util.List;

public record RemoteEventPage(
    List<RemoteEvent> events,
    String nextPageToken,
    String nextSyncToken) {}
