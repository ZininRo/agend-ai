package com.example.agend_ai.repository;

import com.example.agend_ai.entity.AuthSession;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuthSessionRepository extends JpaRepository<AuthSession, UUID> {
  Optional<AuthSession> findByRefreshTokenHash(String refreshTokenHash);
  List<AuthSession> findAllByFamilyId(UUID familyId);

  @Modifying
  @Query("delete from AuthSession s where s.expiresAt < :before or s.revokedAt < :before")
  int deleteExpiredOrRevokedBefore(@Param("before") Instant before);
}
