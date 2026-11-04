package com.example.agend_ai.entity;

import com.example.agend_ai.crypto.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "calendar_connections")
public class CalendarConnection extends BaseEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @Column(name = "provider", nullable = false, length = 32)
  private String provider;

  @Convert(converter = EncryptedStringConverter.class)
  @Column(name = "access_token_enc", columnDefinition = "TEXT")
  private String accessToken;

  @Convert(converter = EncryptedStringConverter.class)
  @Column(name = "refresh_token_enc", columnDefinition = "TEXT")
  private String refreshToken;

  @Column(name = "access_token_expires_at")
  private Instant accessTokenExpiresAt;

  @Column(name = "granted_scopes", columnDefinition = "TEXT")
  private String grantedScopes;

  @Column(name = "status", nullable = false, length = 32)
  private String status = "CONNECTED";

  @Column(name = "sync_enabled", nullable = false)
  private boolean syncEnabled = true;

  @Column(name = "last_sync_at")
  private Instant lastSyncAt;

  @Column(name = "last_error", length = 255)
  private String lastError;
}
