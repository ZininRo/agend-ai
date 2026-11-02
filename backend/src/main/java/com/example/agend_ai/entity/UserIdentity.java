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
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
    name = "user_identities",
    uniqueConstraints = @UniqueConstraint(name = "uk_user_identity_provider_subject", columnNames = {"provider", "provider_subject"}))
public class UserIdentity extends BaseEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @Column(name = "provider", nullable = false, length = 32)
  private String provider;

  @Column(name = "provider_subject", nullable = false, length = 255)
  private String providerSubject;

  @Convert(converter = EncryptedStringConverter.class)
  @Column(name = "email_enc", columnDefinition = "TEXT")
  private String email;

  @Column(name = "email_hash", length = 64)
  private String emailHash;

  @Column(name = "password_hash", length = 100)
  private String passwordHash;
}
