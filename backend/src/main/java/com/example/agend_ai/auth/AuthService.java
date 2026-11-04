package com.example.agend_ai.auth;

import com.example.agend_ai.api.error.AppException;
import com.example.agend_ai.api.error.ErrorCode;
import com.example.agend_ai.auth.dto.AuthUserDto;
import com.example.agend_ai.auth.dto.LoginRequest;
import com.example.agend_ai.auth.dto.RegisterRequest;
import com.example.agend_ai.auth.dto.UpdatePreferencesRequest;
import com.example.agend_ai.config.AuthProperties;
import com.example.agend_ai.entity.AuthSession;
import com.example.agend_ai.entity.User;
import com.example.agend_ai.entity.UserIdentity;
import com.example.agend_ai.repository.AuthSessionRepository;
import com.example.agend_ai.repository.UserIdentityRepository;
import com.example.agend_ai.repository.UserRepository;
import com.example.agend_ai.security.CurrentUser;
import com.example.agend_ai.service.tts.TtsVoiceCatalog;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.zone.ZoneRulesException;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {
  static final String LOCAL = "LOCAL";
  private static final SecureRandom RANDOM = new SecureRandom();

  private final UserRepository users;
  private final UserIdentityRepository identities;
  private final AuthSessionRepository sessions;
  private final PasswordEncoder passwords;
  private final JwtService jwt;
  private final AuthProperties properties;
  private final CurrentUser currentUser;
  private final TtsVoiceCatalog ttsVoices;
  private final Clock clock;

  @Transactional
  public AuthTokens register(RegisterRequest request) {
    String email = normalizeEmail(request.email());
    String emailHash = TokenHash.sha256(email);
    if (identities.existsByEmailHash(emailHash)) throw new AppException(ErrorCode.ACCOUNT_EXISTS);

    User user = new User();
    user.setDisplayName(normalizeDisplayName(request.displayName(), email));
    user.setTimezone(normalizeTimezone(request.timezone()));
    user.setTtsVoice(ttsVoices.defaultVoice());
    users.save(user);

    UserIdentity identity = new UserIdentity();
    identity.setUser(user);
    identity.setProvider(LOCAL);
    identity.setProviderSubject(emailHash);
    identity.setEmail(email);
    identity.setEmailHash(emailHash);
    identity.setPasswordHash(passwords.encode(request.password()));
    try {
      identities.saveAndFlush(identity);
    } catch (DataIntegrityViolationException duplicate) {
      throw new AppException(ErrorCode.ACCOUNT_EXISTS);
    }
    return issueSession(user, email, UUID.randomUUID());
  }

  @Transactional
  public AuthTokens login(LoginRequest request) {
    String email = normalizeEmail(request.email());
    String subject = TokenHash.sha256(email);
    UserIdentity identity = identities.findByProviderAndProviderSubject(LOCAL, subject).orElse(null);
    if (identity == null) {
      passwords.encode(request.password());
      throw new AppException(ErrorCode.AUTH_INVALID);
    }
    if (identity.getPasswordHash() == null || !passwords.matches(request.password(), identity.getPasswordHash()))
      throw new AppException(ErrorCode.AUTH_INVALID);
    return issueSession(identity.getUser(), identity.getEmail(), UUID.randomUUID());
  }

  @Transactional
  public AuthTokens refresh(String rawRefreshToken) {
    if (rawRefreshToken == null || rawRefreshToken.isBlank())
      throw new AppException(ErrorCode.AUTH_SESSION_EXPIRED);
    Instant now = clock.instant();
    AuthSession old =
        sessions
            .findByRefreshTokenHash(TokenHash.sha256(rawRefreshToken))
            .orElseThrow(() -> new AppException(ErrorCode.AUTH_SESSION_EXPIRED));

    if (old.getRevokedAt() != null) {
      // Refresh cookie is shared by browser tabs. Two tabs can legitimately refresh the same
      // cookie almost simultaneously; allow a short rotation grace window instead of treating
      // that race as token theft. A replay outside the window revokes the whole family.
      boolean concurrentRotation =
          old.getReplacedById() != null
              && old.getLastUsedAt() != null
              && !old.getLastUsedAt()
                  .plusSeconds(properties.refreshReuseGraceSeconds())
                  .isBefore(now);
      if (concurrentRotation) {
        User user = old.getUser();
        return issueSession(user, localEmail(user.getId()), old.getFamilyId());
      }
      revokeFamily(old.getFamilyId(), now);
      throw new AppException(ErrorCode.AUTH_SESSION_EXPIRED);
    }
    if (!old.getExpiresAt().isAfter(now)) {
      old.setRevokedAt(now);
      throw new AppException(ErrorCode.AUTH_SESSION_EXPIRED);
    }

    old.setRevokedAt(now);
    old.setLastUsedAt(now);
    User user = old.getUser();
    String email = localEmail(user.getId());
    AuthTokens rotated = issueSession(user, email, old.getFamilyId());
    AuthSession replacement =
        sessions
            .findByRefreshTokenHash(TokenHash.sha256(rotated.refreshToken()))
            .orElseThrow();
    old.setReplacedById(replacement.getId());
    return rotated;
  }

  @Transactional
  public void logout(String rawRefreshToken) {
    if (rawRefreshToken == null || rawRefreshToken.isBlank()) return;
    sessions
        .findByRefreshTokenHash(TokenHash.sha256(rawRefreshToken))
        .filter(s -> s.getRevokedAt() == null)
        .ifPresent(s -> s.setRevokedAt(clock.instant()));
  }

  @Transactional(readOnly = true)
  public AuthUserDto me() {
    User user = users.findById(currentUser.id()).orElseThrow(() -> new AppException(ErrorCode.AUTH_REQUIRED));
    return toDto(user, localEmail(user.getId()));
  }

  @Transactional
  public AuthUserDto updatePreferences(UpdatePreferencesRequest request) {
    User user =
        users.findById(currentUser.id())
            .orElseThrow(() -> new AppException(ErrorCode.AUTH_REQUIRED));
    user.setTtsVoice(ttsVoices.requireSupported(request.ttsVoice()));
    return toDto(user, localEmail(user.getId()));
  }

  private AuthTokens issueSession(User user, String email, UUID familyId) {
    String refresh = randomToken();
    Instant now = clock.instant();
    AuthSession session = new AuthSession();
    session.setUser(user);
    session.setFamilyId(familyId);
    session.setRefreshTokenHash(TokenHash.sha256(refresh));
    session.setExpiresAt(now.plus(Duration.ofDays(properties.refreshTokenDays())));
    sessions.saveAndFlush(session);

    JwtService.IssuedAccessToken access = jwt.issue(user);
    return new AuthTokens(
        access.value(), access.expiresInSeconds(), refresh, toDto(user, email));
  }

  private AuthUserDto toDto(User user, String email) {
    return AuthUserDto.from(user, email, ttsVoices.resolve(user.getTtsVoice()));
  }

  private String localEmail(UUID userId) {
    return identities.findFirstByUser_IdAndProvider(userId, LOCAL).map(UserIdentity::getEmail).orElse(null);
  }

  private void revokeFamily(UUID familyId, Instant when) {
    for (AuthSession session : sessions.findAllByFamilyId(familyId)) {
      if (session.getRevokedAt() == null) session.setRevokedAt(when);
    }
  }

  private static String randomToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String normalizeEmail(String raw) {
    return raw.strip().toLowerCase(Locale.ROOT);
  }

  private static String normalizeDisplayName(String raw, String email) {
    String value = raw == null ? "" : raw.strip();
    if (value.isEmpty()) value = email.substring(0, email.indexOf('@'));
    return value.length() <= 100 ? value : value.substring(0, 100);
  }

  private static String normalizeTimezone(String raw) {
    if (raw == null || raw.isBlank()) return "Europe/Moscow";
    try {
      return ZoneId.of(raw.strip()).getId();
    } catch (ZoneRulesException invalid) {
      throw new AppException(ErrorCode.REQUEST_INVALID);
    }
  }
}
