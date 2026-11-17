package dev.untangle.ticket.application;

import dev.untangle.common.error.ErrorCode;
import dev.untangle.ticket.domain.Category;
import dev.untangle.ticket.domain.Priority;
import dev.untangle.ticket.domain.TicketAction;
import dev.untangle.ticket.domain.TicketSource;
import dev.untangle.ticket.domain.TicketStatus;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Response shapes for tickets. Built by {@link TicketMapper}; never entities. */
public final class TicketViews {

  private TicketViews() {}

  public record AgentRef(UUID id, String fullName) {}

  /** One row in the ticket list / dashboard table. */
  public record TicketSummary(
      UUID id,
      String ticketNumber,
      String subject,
      String customerName,
      Category category,
      Priority priority,
      TicketStatus status,
      TicketSource source,
      AgentRef assignee,
      int escalationLevel,
      Instant slaDueAt,
      boolean slaBreached,
      Instant createdAt,
      Instant updatedAt) {}

  /** Why an action is unavailable, so the UI can explain a disabled button. */
  public record DeniedAction(TicketAction action, ErrorCode reason) {}

  /** The full ticket for the detail page. */
  public record TicketDetail(
      UUID id,
      String ticketNumber,
      String subject,
      String description,
      String customerName,
      String customerEmail,
      Category category,
      Priority priority,
      TicketStatus status,
      TicketSource source,
      String partnerCode,
      String externalEventId,
      AgentRef assignee,
      AgentRef createdBy,
      int escalationLevel,
      int reopenedCount,
      Instant slaStartedAt,
      Instant slaDueAt,
      boolean slaBreached,
      long slaRemainingSeconds,
      Instant firstResponseAt,
      Instant resolvedAt,
      Instant closedAt,
      Instant createdAt,
      Instant updatedAt,
      long version,
      Set<TicketAction> allowedActions,
      List<DeniedAction> deniedActions) {}

  /** Result of a bulk reassignment: what moved and what was skipped (with why). */
  public record BulkAssignResult(List<UUID> assigned, List<Skipped> skipped) {
    public record Skipped(UUID ticketId, ErrorCode reason) {}
  }
}
