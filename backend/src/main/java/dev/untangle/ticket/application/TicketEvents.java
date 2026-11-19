package dev.untangle.ticket.application;

import dev.untangle.ticket.domain.TicketSource;
import java.util.UUID;

/**
 * Domain events published by {@link TicketService}. Listeners (notifications, dashboard cache,
 * metrics) react <b>after commit</b>, so a rolled-back change never notifies anyone. Events keep
 * the ticket feature unaware of who listens.
 */
public final class TicketEvents {

  private TicketEvents() {}

  /** Any change that alters dashboard numbers. */
  public record TicketChanged(UUID ticketId) {}

  public record TicketCreated(UUID ticketId, String ticketNumber, TicketSource source) {}

  /** {@code actorId} is null for automatic actions. */
  public record TicketAssigned(UUID ticketId, String ticketNumber, UUID assigneeId, UUID actorId) {}

  public record TicketTransitioned(
      UUID ticketId, String ticketNumber, String from, String to, UUID actorId) {}

  /**
   * The AI teammate gave a ticket back to the team (ADR-028). {@code toAgentId} is the person it
   * went back to, or null when it went back to the unassigned queue.
   */
  public record TicketHandedBack(
      UUID ticketId, String ticketNumber, UUID toAgentId, UUID fromAgentId, String reason) {}

  public record TicketReopened(UUID ticketId, String ticketNumber, UUID assigneeId, UUID actorId) {}

  public record TicketEscalated(
      UUID ticketId,
      String ticketNumber,
      int level,
      UUID assigneeId,
      UUID actorId,
      boolean automatic,
      String reason) {}
}
