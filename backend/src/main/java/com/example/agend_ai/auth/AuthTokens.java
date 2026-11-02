package com.example.agend_ai.auth;

import com.example.agend_ai.auth.dto.AuthUserDto;

public record AuthTokens(
    String accessToken,
    long accessExpiresInSeconds,
    String refreshToken,
    AuthUserDto user) {}
