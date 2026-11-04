package com.example.agend_ai.calendar.dto;

public record CalendarSyncResponse(int calendarsSynced, int eventsApplied, int outboundProcessed) {}
