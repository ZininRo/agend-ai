package com.example.agend_ai.api;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Docker/Testcontainers is the default. Explicit external PostgreSQL is useful in Docker-less CI.
 */
abstract class DatabaseTestSupport {
  private static PostgreSQLContainer<?> container;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    String url = System.getProperty("test.db.url");
    if (url != null) {
      registry.add("spring.datasource.url", () -> url);
      registry.add(
          "spring.datasource.username", () -> System.getProperty("test.db.user", "postgres"));
      registry.add("spring.datasource.password", () -> System.getProperty("test.db.password", ""));
    } else {
      if (container == null) {
        container = new PostgreSQLContainer<>("postgres:16");
        container.start();
      }
      registry.add("spring.datasource.url", container::getJdbcUrl);
      registry.add("spring.datasource.username", container::getUsername);
      registry.add("spring.datasource.password", container::getPassword);
    }
  }
}
