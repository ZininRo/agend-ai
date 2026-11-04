package com.example.agend_ai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
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
@Table(name = "item_calendar_links")
public class ItemCalendarLink extends BaseEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "item_id", nullable = false, unique = true)
  private Item item;

  @Column(name = "item_id", insertable = false, updatable = false)
  private UUID itemId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "external_calendar_id", nullable = false)
  private ExternalCalendar externalCalendar;

  @Column(name = "external_event_id", nullable = false, length = 1024)
  private String externalEventId;

  @Column(name = "external_event_id_hash", nullable = false, length = 64)
  private String externalEventIdHash;

  @Column(name = "external_etag", length = 255)
  private String externalEtag;

  @Column(name = "external_updated_at")
  private Instant externalUpdatedAt;

  @Column(name = "last_synced_at")
  private Instant lastSyncedAt;
}
