package dev.untangle.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Starts a real PostgreSQL 18 in Docker for tests, the same image as {@code docker-compose.yml}.
 *
 * <p>{@code @ServiceConnection} tells Spring Boot to point the datasource at this container
 * automatically: no URLs or ports in test config. Spring caches test contexts, so all tests that
 * share a configuration also share one container, which keeps the suite fast.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

  public static final DockerImageName POSTGRES_IMAGE =
      DockerImageName.parse("postgres:18.7-alpine3.24").asCompatibleSubstituteFor("postgres");

  @Bean
  @ServiceConnection
  PostgreSQLContainer postgres() {
    return new PostgreSQLContainer(POSTGRES_IMAGE);
  }
}
