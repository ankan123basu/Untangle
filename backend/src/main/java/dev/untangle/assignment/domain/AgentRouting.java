package dev.untangle.assignment.domain;

import dev.untangle.common.audit.AuditedEntity;
import dev.untangle.ticket.domain.Category;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * How one agent takes automatic work (table {@code agent_routing}, ADR-029): whether they take it
 * at all, and which categories they handle (none = every category).
 *
 * <p>An agent without a row uses the defaults ({@link #defaultAutoAssign}). The time of their last
 * automatic ticket is written by a plain UPDATE, not through this entity, so the assignment run
 * never conflicts with a lead editing the settings.
 */
@Getter
@Entity
@Table(name = "agent_routing")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgentRouting extends AuditedEntity {

  @Column(name = "agent_id", nullable = false, updatable = false)
  private UUID agentId;

  @Column(name = "auto_assign", nullable = false)
  private boolean autoAssign;

  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(name = "agent_routing_skills", joinColumns = @JoinColumn(name = "routing_id"))
  @Enumerated(EnumType.STRING)
  @Column(name = "category", nullable = false, length = 20)
  private Set<Category> skills = new HashSet<>();

  @Column(name = "last_auto_assigned_at", insertable = false, updatable = false)
  private Instant lastAutoAssignedAt;

  public AgentRouting(UUID agentId, boolean autoAssign, Set<Category> skills) {
    this.agentId = Objects.requireNonNull(agentId, "agentId");
    change(autoAssign, skills);
  }

  /**
   * Defaults for an agent nobody has configured: people with the AGENT role take automatic work;
   * leads and admins (who coordinate) and the AI teammate (opt-in) don't.
   */
  public static boolean defaultAutoAssign(boolean isAgentRole, boolean ai) {
    return isAgentRole && !ai;
  }

  public void change(boolean autoAssign, Set<Category> skills) {
    this.autoAssign = autoAssign;
    this.skills.clear();
    if (skills != null) {
      this.skills.addAll(skills);
    }
  }

  public Set<Category> getSkills() {
    return Collections.unmodifiableSet(skills);
  }
}
