package dev.untangle.ticket.domain;

import dev.untangle.agent.domain.Agent;
import dev.untangle.common.audit.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One resolution of a ticket (table {@code ticket_resolutions}, ADR-030): the fact row behind the
 * agent performance stats.
 *
 * <p>Credit goes to the <b>assignee</b> at the moment of resolving, not to whoever pressed the
 * button: a lead approving Unty's proposal credits Unty. The row is written once; the only later
 * change is {@code reopened_at}, set by a bulk update when the resolution doesn't stick.
 */
@Getter
@Entity
@Table(name = "ticket_resolutions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TicketResolution extends BaseEntity {

  @Column(name = "ticket_id", nullable = false, updatable = false)
  private UUID ticketId;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "agent_id", nullable = false, updatable = false)
  private Agent agent;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "resolved_by_agent_id", updatable = false)
  private Agent resolvedBy;

  @Enumerated(EnumType.STRING)
  @Column(name = "priority", nullable = false, length = 10, updatable = false)
  private Priority priority;

  @Enumerated(EnumType.STRING)
  @Column(name = "category", nullable = false, length = 20, updatable = false)
  private Category category;

  @Column(name = "opened_at", nullable = false, updatable = false)
  private Instant openedAt;

  @Column(name = "assigned_at", nullable = false, updatable = false)
  private Instant assignedAt;

  @Column(name = "resolved_at", nullable = false, updatable = false)
  private Instant resolvedAt;

  @Column(name = "sla_due_at", nullable = false, updatable = false)
  private Instant slaDueAt;

  @Column(name = "reopened_at", insertable = false, updatable = false)
  private Instant reopenedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  /**
   * Records that {@code ticket} was just resolved by {@code actor}. The ticket must already be
   * RESOLVED and assigned (the state machine guarantees an owner for IN_PROGRESS → RESOLVED).
   */
  public static TicketResolution of(Ticket ticket, Agent actor) {
    if (ticket.getStatus() != TicketStatus.RESOLVED
        || ticket.getResolvedAt() == null
        || ticket.getAssignedAgent() == null) {
      throw new IllegalStateException("Only a resolved, assigned ticket has a resolution");
    }
    TicketResolution r = new TicketResolution();
    r.ticketId = ticket.getId();
    r.agent = ticket.getAssignedAgent();
    r.resolvedBy = actor;
    r.priority = ticket.getPriority();
    r.category = ticket.getCategory();
    r.resolvedAt = ticket.getResolvedAt();
    r.openedAt = min(ticket.getSlaStartedAt(), r.resolvedAt);
    Instant assigned = ticket.getAssignedAt() == null ? r.openedAt : ticket.getAssignedAt();
    // Clamp into [opened, resolved] so the row always satisfies ck_resolutions_order.
    r.assignedAt = min(max(assigned, r.openedAt), r.resolvedAt);
    r.slaDueAt = ticket.getSlaDueAt();
    r.createdAt = r.resolvedAt;
    return r;
  }

  private static Instant min(Instant a, Instant b) {
    return a.isBefore(b) ? a : b;
  }

  private static Instant max(Instant a, Instant b) {
    return a.isAfter(b) ? a : b;
  }
}
