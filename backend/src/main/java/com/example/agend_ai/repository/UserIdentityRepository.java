package com.example.agend_ai.repository;

import com.example.agend_ai.entity.UserIdentity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserIdentityRepository extends JpaRepository<UserIdentity, UUID> {
  Optional<UserIdentity> findByProviderAndProviderSubject(String provider, String providerSubject);
  Optional<UserIdentity> findFirstByUser_IdAndProvider(UUID userId, String provider);
  boolean existsByEmailHash(String emailHash);
}
