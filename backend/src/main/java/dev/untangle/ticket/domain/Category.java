package dev.untangle.ticket.domain;

/** What a complaint is about (A7). Used for filtering, routing and AI triage suggestions. */
public enum Category {
  BILLING,
  TECHNICAL,
  ACCOUNT,
  REFUND,
  GENERAL,
  OTHER
}
