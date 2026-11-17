package dev.untangle.security.rbac;

/**
 * Fine-grained capabilities (MASTER_PROMPT §6.2). Code checks permissions, never role names, so
 * "who can do what" lives in exactly one place: {@link RolePermissions}.
 *
 * <p>Permissions marked <i>own</i> also need an ownership check ("is this my ticket?"), done by
 * {@code TicketAccessPolicy} in Phase 3.
 */
public enum Permission {
  TICKET_VIEW,
  DASHBOARD_VIEW,
  TICKET_CREATE,
  /** Take an unassigned ticket for yourself. */
  TICKET_SELF_ASSIGN,
  /** Assign or reassign any ticket to anyone. */
  TICKET_ASSIGN_ANY,
  /** <i>own</i>: transition tickets assigned to you. */
  TICKET_TRANSITION_OWN,
  TICKET_TRANSITION_ANY,
  /** Close any ticket (OPEN→CLOSED as spam/duplicate, RESOLVED→CLOSED). */
  TICKET_CLOSE,
  /** <i>own</i>, configurable: an agent may close their own RESOLVED ticket. */
  TICKET_CLOSE_OWN_RESOLVED,
  TICKET_REOPEN,
  TICKET_COMMENT,
  /** <i>own</i>: edit subject/description/category/priority of your tickets. */
  TICKET_EDIT_OWN,
  TICKET_EDIT_ANY,
  TICKET_ESCALATE,
  AGENT_WORKLOAD_VIEW,
  AI_USE,
  AGENT_MANAGE,
  PARTNER_MANAGE,
  WEBHOOK_LOG_VIEW
}
