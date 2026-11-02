package com.example.agend_ai.auth;

import com.example.agend_ai.auth.dto.AuthResponse;
import com.example.agend_ai.auth.dto.AuthUserDto;
import com.example.agend_ai.auth.dto.LoginRequest;
import com.example.agend_ai.auth.dto.RegisterRequest;
import com.example.agend_ai.config.AuthProperties;
import jakarta.validation.Valid;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
  public static final String REFRESH_COOKIE = "agendai_refresh";

  private final AuthService auth;
  private final AuthProperties properties;

  @PostMapping("/register")
  public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
    return respond(auth.register(request));
  }

  @PostMapping("/login")
  public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
    return respond(auth.login(request));
  }

  @PostMapping("/refresh")
  public ResponseEntity<AuthResponse> refresh(
      @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
    return respond(auth.refresh(refreshToken));
  }

  @PostMapping("/logout")
  public ResponseEntity<Void> logout(
      @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
    auth.logout(refreshToken);
    return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, clearCookie().toString()).build();
  }

  @GetMapping("/me")
  public AuthUserDto me() {
    return auth.me();
  }

  private ResponseEntity<AuthResponse> respond(AuthTokens tokens) {
    AuthResponse body =
        new AuthResponse(tokens.accessToken(), "Bearer", tokens.accessExpiresInSeconds(), tokens.user());
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, refreshCookie(tokens.refreshToken()).toString())
        .body(body);
  }

  private ResponseCookie refreshCookie(String token) {
    return ResponseCookie.from(REFRESH_COOKIE, token)
        .httpOnly(true)
        .secure(properties.cookieSecure())
        .sameSite(properties.cookieSameSite())
        .path("/api/auth")
        .maxAge(Duration.ofDays(properties.refreshTokenDays()))
        .build();
  }

  private ResponseCookie clearCookie() {
    return ResponseCookie.from(REFRESH_COOKIE, "")
        .httpOnly(true)
        .secure(properties.cookieSecure())
        .sameSite(properties.cookieSameSite())
        .path("/api/auth")
        .maxAge(Duration.ZERO)
        .build();
  }
}
