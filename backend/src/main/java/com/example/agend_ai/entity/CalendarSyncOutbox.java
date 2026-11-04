package com.example.agend_ai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
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
@Table(name = "calendar_sync_outbox")
public class CalendarSyncOutbox extends BaseEntity {
  @Id
  @Column(name = "item_id")
  private UUID itemId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @Column(name = "operation", nullable = false, length = 16)
  private String operation;

  @Column(name = "attempts", nullable = false)
  private int attempts;

  @Column(name = "available_at", nullable = false)
  private Instant availableAt;

  @Column(name = "last_error", length = 255)
  private String lastError;
}
