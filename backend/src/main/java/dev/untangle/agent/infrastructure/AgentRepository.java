package dev.untangle.agent.infrastructure;

import dev.untangle.agent.domain.Agent;
import dev.untangle.agent.domain.Role;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentRepository extends JpaRepository<Agent, UUID> {

  /**
   * Login lookup. Spring Data turns {@code IgnoreCase} into {@code lower(email) = lower(?)}, which
   * matches the {@code ux_agents_email_lower} index exactly.
   */
  Optional<Agent> findByEmailIgnoreCase(String email);

  boolean existsByEmailIgnoreCase(String email);

  /** "Last active admin" safety rule: never deactivate or demote the final one. */
  long countByRoleAndActiveTrue(Role role);

  /** Recipients for SLA-breach and escalation notifications. */
  List<Agent> findAllByRoleAndActiveTrue(Role role);

  /** Assignment dropdowns. */
  List<Agent> findAllByActiveTrueOrderByFullNameAsc();
}
