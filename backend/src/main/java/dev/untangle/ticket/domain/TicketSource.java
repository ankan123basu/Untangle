package dev.untangle.ticket.domain;

/** Which "front door" created the ticket: an agent in the UI, or a partner's signed webhook. */
public enum TicketSource {
  MANUAL,
  WEBHOOK
}
