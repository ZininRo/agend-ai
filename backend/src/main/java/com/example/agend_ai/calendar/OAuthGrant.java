package com.example.agend_ai.calendar;

public record OAuthGrant(
    String accessToken,
    String refreshToken,
    long expiresInSeconds,
    String scopes) {}
