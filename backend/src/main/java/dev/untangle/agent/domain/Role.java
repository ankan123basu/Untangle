package dev.untangle.agent.domain;

/**
 * What a staff member is allowed to do. Mapped to fine-grained permissions in Phase 2.
 *
 * <ul>
 *   <li>{@link #AGENT}: works tickets assigned to them, self-assigns from the queue.
 *   <li>{@link #LEAD}: everything an agent can, plus assign anyone, close and reopen.
 *   <li>{@link #ADMIN}: everything, plus managing agents and partner integrations.
 * </ul>
 */
public enum Role {
  ADMIN,
  LEAD,
  AGENT
}
