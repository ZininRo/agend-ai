package com.example.agend_ai.calendar.google;

public class GoogleApiException extends RuntimeException {
  private final int status;

  public GoogleApiException(int status) {
    super("Google API HTTP " + status, null, false, false);
    this.status = status;
  }

  public int status() {
    return status;
  }
}
