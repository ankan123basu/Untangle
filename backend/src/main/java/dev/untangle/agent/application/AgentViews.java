package dev.untangle.agent.application;

import dev.untangle.agent.domain.Agent;
import dev.untangle.agent.domain.Role;
import java.time.Instant;
import java.util.UUID;

/** Response shapes for agents. Records, never entities, so they are safe to serialise. */
public final class AgentViews {

  private AgentViews() {}

  /** Minimal view for everyone: assignment dropdowns, avatars. No email (PII minimisation). */
  public record AgentOption(UUID id, String fullName, Role role) {
    static AgentOption of(Agent a) {
      return new AgentOption(a.getId(), a.getFullName(), a.getRole());
    }
  }

  /** Full view for the admin screen. */
  public record AgentAdminView(
      UUID id,
      String fullName,
      String email,
      Role role,
      boolean active,
      boolean locked,
      Instant lastLoginAt,
      Instant createdAt,
      long version) {
    static AgentAdminView of(Agent a, Instant now) {
      return new AgentAdminView(
          a.getId(),
          a.getFullName(),
          a.getEmail(),
          a.getRole(),
          a.isActive(),
          a.isLockedAt(now),
          a.getLastLoginAt(),
          a.getCreatedAt(),
          a.getVersion());
    }
  }
}
