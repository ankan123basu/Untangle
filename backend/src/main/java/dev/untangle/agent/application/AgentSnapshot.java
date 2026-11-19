package dev.untangle.agent.application;

import dev.untangle.agent.domain.Agent;
import dev.untangle.agent.domain.Role;
import java.io.Serializable;
import java.util.UUID;

/**
 * The few agent facts security needs on every request. Immutable and cacheable; it's not an entity,
 * so it can safely leave a transaction.
 */
public record AgentSnapshot(
    UUID id, String email, String fullName, Role role, boolean active, boolean ai)
    implements Serializable {

  public static AgentSnapshot of(Agent agent) {
    return new AgentSnapshot(
        agent.getId(),
        agent.getEmail(),
        agent.getFullName(),
        agent.getRole(),
        agent.isActive(),
        agent.isAi());
  }
}
