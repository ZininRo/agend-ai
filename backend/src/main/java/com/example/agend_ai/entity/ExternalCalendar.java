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
@Table(name = "external_calendars")
public class ExternalCalendar extends BaseEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "connection_id", nullable = false)
  private CalendarConnection connection;

  @Convert(converter = EncryptedStringConverter.class)
  @Column(name = "provider_calendar_id_enc", nullable = false, columnDefinition = "TEXT")
  private String providerCalendarId;

  @Column(name = "provider_calendar_id_hash", nullable = false, length = 64)
  private String providerCalendarIdHash;

  @Convert(converter = EncryptedStringConverter.class)
  @Column(name = "summary_enc", columnDefinition = "TEXT")
  private String summary;

  @Column(name = "timezone", length = 64)
  private String timezone;

  @Column(name = "access_role", length = 32)
  private String accessRole;

  @Column(name = "primary_calendar", nullable = false)
  private boolean primaryCalendar;

  @Column(name = "available", nullable = false)
  private boolean available = true;

  @Column(name = "sync_enabled", nullable = false)
  private boolean syncEnabled;

  @Column(name = "write_target", nullable = false)
  private boolean writeTarget;

  @Convert(converter = EncryptedStringConverter.class)
  @Column(name = "sync_token_enc", columnDefinition = "TEXT")
  private String syncToken;

  @Column(name = "last_sync_at")
  private Instant lastSyncAt;

  @Column(name = "last_full_sync_at")
  private Instant lastFullSyncAt;

  @Column(name = "sync_requested_at")
  private Instant syncRequestedAt;

  @Column(name = "watch_channel_id", length = 128)
  private String watchChannelId;

  @Column(name = "watch_resource_id", length = 255)
  private String watchResourceId;

  @Column(name = "watch_token_hash", length = 64)
  private String watchTokenHash;

  @Column(name = "watch_expires_at")
  private Instant watchExpiresAt;

  public boolean writable() {
    return "owner".equalsIgnoreCase(accessRole)
        || "writer".equalsIgnoreCase(accessRole)
        || "writerWithoutPrivateAccess".equalsIgnoreCase(accessRole);
  }
}
