package com.example.agend_ai.auth.dto;

public record AuthResponse(String accessToken, String tokenType, long expiresIn, AuthUserDto user) {}
