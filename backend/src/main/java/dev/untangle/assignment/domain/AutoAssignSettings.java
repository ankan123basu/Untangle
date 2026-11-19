package dev.untangle.assignment.domain;

import dev.untangle.common.audit.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * The auto-assign switch and the per-agent cap (table {@code auto_assign_settings}, one row,
 * ADR-029). No row means switched off with the default cap.
 */
@Getter
@Entity
@Table(name = "auto_assign_settings")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AutoAssignSettings extends AuditedEntity {

  public static final int DEFAULT_MAX_OPEN = 10;
  public static final int MAX_CAP = 100;

  @Column(name = "enabled", nullable = false)
  private boolean enabled;

  @Column(name = "max_open_per_agent", nullable = false)
  private int maxOpenPerAgent = DEFAULT_MAX_OPEN;

  @Column(name = "updated_by_agent_id")
  private UUID updatedByAgentId;

  /** Switched off, default cap: what an empty table means. */
  public static AutoAssignSettings defaults() {
    return new AutoAssignSettings();
  }

  public void change(boolean enabled, int maxOpenPerAgent, UUID by) {
    if (maxOpenPerAgent < 1 || maxOpenPerAgent > MAX_CAP) {
      throw new IllegalArgumentException("maxOpenPerAgent must be between 1 and " + MAX_CAP);
    }
    this.enabled = enabled;
    this.maxOpenPerAgent = maxOpenPerAgent;
    this.updatedByAgentId = by;
  }
}
