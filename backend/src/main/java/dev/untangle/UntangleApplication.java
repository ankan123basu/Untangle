package dev.untangle;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the Untangle backend.
 *
 * <p>Untangle is a modular monolith: one deployable Spring Boot app whose code is split into
 * feature packages ({@code ticket}, {@code webhook}, {@code auth}, ...). Each feature owns its own
 * controller, service, repository and DTOs. See {@code docs/architecture.md} and ADR-001.
 */
@SpringBootApplication
public class UntangleApplication {

  public static void main(String[] args) {
    SpringApplication.run(UntangleApplication.class, args);
  }
}
