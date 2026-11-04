package com.example.agend_ai.repository;

import com.example.agend_ai.entity.CalendarConnection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CalendarConnectionRepository extends JpaRepository<CalendarConnection, UUID> {
  Optional<CalendarConnection> findByUser_IdAndProvider(UUID userId, String provider);
  Optional<CalendarConnection> findByIdAndUser_Id(UUID id, UUID userId);
}
