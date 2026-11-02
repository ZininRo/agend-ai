package com.example.agend_ai.auth;

import com.example.agend_ai.config.AuthProperties;
import com.example.agend_ai.entity.User;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class JwtService {
  private final JwtEncoder encoder;
  private final AuthProperties properties;
  private final Clock clock;

  public IssuedAccessToken issue(User user) {
    Instant now = clock.instant();
    Instant expiresAt = now.plus(Duration.ofMinutes(properties.accessTokenMinutes()));
    JwtClaimsSet.Builder claims =
        JwtClaimsSet.builder()
            .issuer(properties.issuer())
            .issuedAt(now)
            .expiresAt(expiresAt)
            .subject(user.getId().toString())
            .claim("typ", "access");
    String value =
        encoder
            .encode(
                JwtEncoderParameters.from(
                    JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
            .getTokenValue();
    return new IssuedAccessToken(value, Duration.between(now, expiresAt).toSeconds());
  }

  public record IssuedAccessToken(String value, long expiresInSeconds) {}
}
