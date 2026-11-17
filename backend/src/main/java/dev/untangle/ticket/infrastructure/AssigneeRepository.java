package dev.untangle.ticket.infrastructure;

import dev.untangle.agent.domain.Agent;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/**
 * The ticket feature's read-only view of agents: just enough to set an assignee or a creator.
 *
 * <p>It's a separate, minimal repository owned by the ticket feature, so tickets don't need the
 * agent feature's services. That keeps the dependency between the two features one-way (the agent
 * feature already asks tickets "does this agent have open work?").
 */
public interface AssigneeRepository extends Repository<Agent, UUID> {

  Optional<Agent> findById(UUID id);

  /** A lazy reference (no SELECT) for a foreign key whose existence is already known. */
  Agent getReferenceById(UUID id);
}
