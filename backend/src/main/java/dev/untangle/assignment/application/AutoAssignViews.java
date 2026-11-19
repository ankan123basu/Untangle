package dev.untangle.assignment.application;

import dev.untangle.agent.domain.Role;
import dev.untangle.ticket.domain.Category;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Request and response shapes for auto-assignment (ADR-029). */
public final class AutoAssignViews {

  private AutoAssignViews() {}

  /** Everything the Auto-assign screen shows. */
  public record AutoAssignOverview(
      boolean enabled, int maxOpenPerAgent, long waiting, List<AgentRoutingView> agents) {}

  /**
   * One active agent's routing. {@code load} is the weighted workload the planner compares
   * (CRITICAL 4, HIGH 3, MEDIUM 2, LOW 1 per unresolved ticket).
   */
  public record AgentRoutingView(
      UUID agentId,
      String fullName,
      Role role,
      boolean ai,
      boolean autoAssign,
      List<Category> skills,
      int openTickets,
      int load,
      @Nullable Instant lastAutoAssignedAt) {}

  /** The result of one run: how many tickets were assigned and how many still wait. */
  public record AutoAssignRun(int assigned, long waiting) {}

  public record UpdateSettings(
      @NotNull Boolean enabled, @NotNull @Min(1) @Max(100) Integer maxOpenPerAgent) {}

  /** {@code skills}: the categories this agent handles; empty = every category. */
  public record UpdateAgentRouting(@NotNull Boolean autoAssign, @NotNull Set<Category> skills) {}
}
