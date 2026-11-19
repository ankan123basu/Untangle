package dev.untangle.ticket.application;

import dev.untangle.common.error.ErrorCode;
import dev.untangle.ticket.domain.Category;
import dev.untangle.ticket.domain.Priority;
import dev.untangle.ticket.domain.TicketAction;
import dev.untangle.ticket.domain.TicketSource;
import dev.untangle.ticket.domain.TicketStatus;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Response shapes for tickets. Built by {@link TicketMapper}; never entities. */
public final class TicketViews {

  private TicketViews() {}

  /** {@code ai} marks the AI teammate (ADR-028), so the UI can badge it. */
  public record AgentRef(UUID id, String fullName, boolean ai) {}

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
      @Nullable AgentRef assignee,
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
      @Nullable String customerEmail,
      Category category,
      Priority priority,
      TicketStatus status,
      TicketSource source,
      @Nullable String partnerCode,
      @Nullable String externalEventId,
      @Nullable AgentRef assignee,
      @Nullable AgentRef createdBy,
      int escalationLevel,
      int reopenedCount,
      Instant slaStartedAt,
      Instant slaDueAt,
      boolean slaBreached,
      long slaRemainingSeconds,
      @Nullable Instant firstResponseAt,
      @Nullable Instant resolvedAt,
      @Nullable Instant closedAt,
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
