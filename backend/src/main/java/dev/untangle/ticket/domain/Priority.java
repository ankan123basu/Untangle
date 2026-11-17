package dev.untangle.ticket.domain;

/**
 * How urgent a ticket is (A6). Drives the SLA deadline (Section 5.4).
 *
 * <p>Values are case-sensitive everywhere: {@code "high"} or {@code "URGENT"} are rejected by
 * validation (400) and, as a last line of defence, by the {@code ck_tickets_priority} constraint.
 */
public enum Priority {
  LOW,
  MEDIUM,
  HIGH,
  CRITICAL
}
