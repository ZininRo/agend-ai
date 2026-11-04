package com.example.agend_ai.calendar;

public record RemoteCalendar(
    String id,
    String summary,
    String timezone,
    String accessRole,
    boolean primary) {

  public boolean writable() {
    return "owner".equalsIgnoreCase(accessRole)
        || "writer".equalsIgnoreCase(accessRole)
        || "writerWithoutPrivateAccess".equalsIgnoreCase(accessRole);
  }
}
