package com.example.agend_ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "auth")
public record AuthProperties(
    String jwtSecret,
    @DefaultValue("agend-ai") String issuer,
    @DefaultValue("15") long accessTokenMinutes,
    @DefaultValue("30") long refreshTokenDays,
    @DefaultValue("10") long refreshReuseGraceSeconds,
    @DefaultValue("false") boolean cookieSecure,
    @DefaultValue("Strict") String cookieSameSite) {}
