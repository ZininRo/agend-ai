package com.example.agend_ai.repository;

import com.example.agend_ai.entity.CalendarOAuthState;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CalendarOAuthStateRepository extends JpaRepository<CalendarOAuthState, String> {
  Optional<CalendarOAuthState> findByStateHashAndExpiresAtAfter(String stateHash, Instant now);
  void deleteByExpiresAtBefore(Instant now);
}
