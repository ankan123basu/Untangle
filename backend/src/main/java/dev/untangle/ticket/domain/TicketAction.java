package dev.untangle.ticket.domain;

/**
 * Everything a person can do to a ticket. {@code GET /tickets/{id}} returns the subset the caller
 * may do right now ({@code allowedActions}), computed by {@code TicketAccessPolicy}, the same code
 * that enforces them. The UI renders buttons from this list and never re-implements the rules.
 */
public enum TicketAction {
  EDIT,
  /** Assign or reassign to anyone, or unassign (leads/admins). */
  ASSIGN,
  /** Take an unassigned ticket for yourself. */
  SELF_ASSIGN,
  /** OPEN → IN_PROGRESS. */
  START_PROGRESS,
  /** IN_PROGRESS → OPEN ("release back to queue"). */
  RELEASE,
  /** IN_PROGRESS → RESOLVED (resolution note required). */
  RESOLVE,
  /** RESOLVED → IN_PROGRESS ("the fix didn't work", reason required). */
  RETURN_TO_PROGRESS,
  /** OPEN → CLOSED (reason required) or RESOLVED → CLOSED. */
  CLOSE,
  /** CLOSED → OPEN (reason required), only via {@code /reopen}. */
  REOPEN,
  COMMENT,
  ESCALATE
}
