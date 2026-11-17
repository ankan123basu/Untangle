package dev.untangle.ticket.domain;

import static dev.untangle.ticket.domain.TicketStatus.CLOSED;
import static dev.untangle.ticket.domain.TicketStatus.IN_PROGRESS;
import static dev.untangle.ticket.domain.TicketStatus.OPEN;
import static dev.untangle.ticket.domain.TicketStatus.RESOLVED;

import java.util.Map;
import java.util.Optional;

/**
 * The single source of truth for status changes (MASTER_PROMPT §5.1, docs/state-machine.md).
 *
 * <pre>
 *   OPEN ──start──▶ IN_PROGRESS ──resolve──▶ RESOLVED ──close──▶ CLOSED
 *    │ ◀──release──     ▲  ◀──return to progress──┘                │
 *    └───────────close (spam/duplicate)──────────────────────────▶ │
 *    ◀──────────────────────────reopen──────────────────────────────┘
 * </pre>
 *
 * Anything not in the table, including "same status", is invalid. Who may perform a move is decided
 * separately by {@code TicketAccessPolicy}.
 */
public final class TicketStateMachine {

  private record Edge(TicketStatus from, TicketStatus to) {}

  private static final Map<Edge, TicketAction> EDGES =
      Map.of(
          new Edge(OPEN, IN_PROGRESS), TicketAction.START_PROGRESS,
          new Edge(OPEN, CLOSED), TicketAction.CLOSE,
          new Edge(IN_PROGRESS, OPEN), TicketAction.RELEASE,
          new Edge(IN_PROGRESS, RESOLVED), TicketAction.RESOLVE,
          new Edge(RESOLVED, IN_PROGRESS), TicketAction.RETURN_TO_PROGRESS,
          new Edge(RESOLVED, CLOSED), TicketAction.CLOSE,
          new Edge(CLOSED, OPEN), TicketAction.REOPEN);

  private TicketStateMachine() {}

  /** The action that performs {@code from → to}, or empty if the move is not allowed. */
  public static Optional<TicketAction> actionFor(TicketStatus from, TicketStatus to) {
    return Optional.ofNullable(EDGES.get(new Edge(from, to)));
  }

  public static boolean isAllowed(TicketStatus from, TicketStatus to) {
    return EDGES.containsKey(new Edge(from, to));
  }

  /** The status an action leads to from {@code from}, if that action is a status change there. */
  public static Optional<TicketStatus> targetOf(TicketAction action, TicketStatus from) {
    return EDGES.entrySet().stream()
        .filter(e -> e.getKey().from() == from && e.getValue() == action)
        .map(e -> e.getKey().to())
        .findFirst();
  }

  /** A written reason is required for these moves (§5.1). */
  public static boolean requiresReason(TicketStatus from, TicketStatus to) {
    return (from == OPEN && to == CLOSED)
        || (from == RESOLVED && to == IN_PROGRESS)
        || (from == CLOSED && to == OPEN);
  }

  /** A4: resolving requires a resolution note. */
  public static boolean requiresResolutionNote(TicketStatus to) {
    return to == RESOLVED;
  }
}
