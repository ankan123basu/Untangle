package dev.untangle.assignment.infrastructure;

import dev.untangle.assignment.domain.AgentRouting;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AgentRoutingRepository extends JpaRepository<AgentRouting, UUID> {

  Optional<AgentRouting> findByAgentId(UUID agentId);

  /**
   * Records an automatic assignment for the round-robin tie-break. A plain upsert (no version
   * bump), so it never conflicts with a lead editing the agent's settings at the same moment.
   */
  @Modifying
  @Query(
      value =
          "insert into agent_routing (agent_id, auto_assign, last_auto_assigned_at)"
              + " values (:agentId, :defaultAutoAssign, :now)"
              + " on conflict (agent_id) do update"
              + " set last_auto_assigned_at = excluded.last_auto_assigned_at",
      nativeQuery = true)
  void markAutoAssigned(UUID agentId, boolean defaultAutoAssign, Instant now);
}
