package com.example.agend_ai.security;

import com.example.agend_ai.api.error.ApiError;
import com.example.agend_ai.api.error.ErrorCode;
import com.example.agend_ai.api.error.RequestIdFilter;
import com.example.agend_ai.config.AuthProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import javax.crypto.SecretKey;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
  @Bean
  SecretKey authSecret(AuthProperties properties) {
    String raw = properties.jwtSecret();
    if (raw == null || raw.isBlank()) {
      throw new IllegalStateException("AUTH_JWT_SECRET must be configured (Base64, at least 32 bytes)");
    }
    byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(raw.strip());
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException("AUTH_JWT_SECRET must be valid Base64", invalid);
    }
    if (bytes.length < 32) throw new IllegalStateException("AUTH_JWT_SECRET must contain at least 32 bytes");
    return new SecretKeySpec(bytes, "HmacSHA256");
  }

  @Bean
  JwtEncoder jwtEncoder(SecretKey secret) {
    return new NimbusJwtEncoder(new ImmutableSecret<>(secret));
  }

  @Bean
  JwtDecoder jwtDecoder(SecretKey secret, AuthProperties properties) {
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withSecretKey(secret).macAlgorithm(MacAlgorithm.HS256).build();
    decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
    return decoder;
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
  }

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http, ObjectMapper mapper, AuthProperties properties) throws Exception {
    var errorWriter = new SecurityErrorWriter(mapper);
    http
        .csrf(csrf -> csrf.disable())
        .cors(Customizer.withDefaults())
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(HttpMethod.OPTIONS, "/**")
                    .permitAll()
                    .requestMatchers("/api/health")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login", "/api/auth/refresh", "/api/auth/logout")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/calendar/google/callback")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/calendar/google/webhook")
                    .permitAll()
                    .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            oauth ->
                oauth.jwt(Customizer.withDefaults())
                    .authenticationEntryPoint((request, response, ex) -> errorWriter.write(response, ErrorCode.AUTH_REQUIRED)))
        .exceptionHandling(
            handlers ->
                handlers
                    .authenticationEntryPoint((request, response, ex) -> errorWriter.write(response, ErrorCode.AUTH_REQUIRED))
                    .accessDeniedHandler((request, response, ex) -> errorWriter.write(response, ErrorCode.ACCESS_DENIED)));

    return http.build();
  }

  private static final class SecurityErrorWriter {
    private final ObjectMapper mapper;

    private SecurityErrorWriter(ObjectMapper mapper) {
      this.mapper = mapper;
    }

    void write(HttpServletResponse response, ErrorCode code) throws IOException {
      response.setStatus(code.httpStatus());
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      mapper.writeValue(
          response.getOutputStream(), ApiError.of(code, null, MDC.get(RequestIdFilter.MDC_KEY)));
    }
  }
}
