package dev.untangle.ticket.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle states. Allowed moves between them live in {@code TicketStateMachine} (Phase 3); see
 * {@code docs/state-machine.md}.
 */
public enum TicketStatus {
  OPEN,
  IN_PROGRESS,
  RESOLVED,
  CLOSED;

  /** A5: "unresolved" means someone still has to act. Used by the dashboard and SLA scheduler. */
  public static final Set<TicketStatus> UNRESOLVED = EnumSet.of(OPEN, IN_PROGRESS);

  public boolean isUnresolved() {
    return UNRESOLVED.contains(this);
  }
}
