package dev.untangle.ticket.domain;

import dev.untangle.agent.domain.Agent;
import dev.untangle.common.audit.AuditedEntity;
import dev.untangle.common.error.ApiException;
import dev.untangle.common.error.ErrorCode;
import dev.untangle.webhook.domain.Partner;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;

/**
 * A customer complaint (table {@code tickets}).
 *
 * <p>Tickets are created only through the two factory methods, one per "front door", so a ticket
 * can never be half-initialised (for example a webhook ticket without its event id). Every change
 * goes through a behaviour method below. Each one starts with the closed-ticket guard (§5.2) and
 * keeps the lifecycle timestamps consistent with the status (mirrored by DB constraints).
 * <b>Who</b> may call them is decided before that, by {@code TicketAccessPolicy}. There are no
 * public setters.
 *
 * <p>Relations are {@code LAZY}: loading a ticket doesn't load its agent or partner until they're
 * used. List queries fetch-join what they display, to avoid N+1 queries (Section 15).
 */
@Getter
@Entity
@Table(name = "tickets")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Ticket extends AuditedEntity {

  /** Assigned by the database ({@code next_ticket_number()}), read back after insert. */
  @Generated
  @Column(name = "ticket_number", nullable = false, insertable = false, updatable = false)
  private String ticketNumber;

  @Column(name = "subject", nullable = false, length = 150)
  private String subject;

  @Column(name = "description", nullable = false, length = 5000)
  private String description;

  @Column(name = "customer_name", nullable = false, length = 120)
  private String customerName;

  @Column(name = "customer_email", length = 254)
  private String customerEmail;

  @Enumerated(EnumType.STRING)
  @Column(name = "category", nullable = false, length = 20)
  private Category category;

  @Enumerated(EnumType.STRING)
  @Column(name = "priority", nullable = false, length = 10)
  private Priority priority;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 15)
  private TicketStatus status = TicketStatus.OPEN;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "assigned_agent_id")
  private Agent assignedAgent;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "created_by_agent_id", updatable = false)
  private Agent createdBy;

  @Enumerated(EnumType.STRING)
  @Column(name = "source", nullable = false, length = 10, updatable = false)
  private TicketSource source;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "partner_id", updatable = false)
  private Partner partner;

  @Column(name = "external_event_id", length = 128, updatable = false)
  private String externalEventId;

  @Column(name = "escalation_level", nullable = false)
  private short escalationLevel;

  @Column(name = "sla_started_at", nullable = false)
  private Instant slaStartedAt;

  @Column(name = "sla_due_at", nullable = false)
  private Instant slaDueAt;

  /** Severity order for sorting (4 = CRITICAL). Maintained by the database (generated column). */
  @Column(name = "priority_rank", insertable = false, updatable = false)
  private Short priorityRank;

  /** Lifecycle order for sorting (1 = OPEN). Maintained by the database (generated column). */
  @Column(name = "status_rank", insertable = false, updatable = false)
  private Short statusRank;

  @Column(name = "first_response_at")
  private Instant firstResponseAt;

  @Column(name = "resolved_at")
  private Instant resolvedAt;

  @Column(name = "closed_at")
  private Instant closedAt;

  @Column(name = "reopened_count", nullable = false)
  private int reopenedCount;

  /**
   * Who last gave this ticket back (released it, or the AI handed it back), so auto-assignment
   * never hands it straight back to them (ADR-029).
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "released_by_agent_id")
  private Agent releasedBy;

  private Ticket(
      String subject,
      String description,
      String customerName,
      String customerEmail,
      Category category,
      Priority priority,
      TicketSource source,
      Instant slaStartedAt,
      Instant slaDueAt) {
    this.subject = Objects.requireNonNull(subject, "subject").strip();
    this.description = Objects.requireNonNull(description, "description").strip();
    this.customerName = Objects.requireNonNull(customerName, "customerName").strip();
    this.customerEmail = customerEmail == null ? null : customerEmail.strip();
    this.category = Objects.requireNonNull(category, "category");
    this.priority = Objects.requireNonNull(priority, "priority");
    this.source = source;
    this.slaStartedAt = Objects.requireNonNull(slaStartedAt, "slaStartedAt");
    this.slaDueAt = Objects.requireNonNull(slaDueAt, "slaDueAt");
  }

  /** A ticket typed in by a support agent. */
  public static Ticket manual(
      String subject,
      String description,
      String customerName,
      String customerEmail,
      Category category,
      Priority priority,
      Agent createdBy,
      Instant slaStartedAt,
      Instant slaDueAt) {
    Ticket ticket =
        new Ticket(
            subject,
            description,
            customerName,
            customerEmail,
            category,
            priority,
            TicketSource.MANUAL,
            slaStartedAt,
            slaDueAt);
    ticket.createdBy = Objects.requireNonNull(createdBy, "createdBy");
    return ticket;
  }

  /** A ticket sent by a partner system through the signed webhook. */
  public static Ticket fromWebhook(
      String subject,
      String description,
      String customerName,
      String customerEmail,
      Category category,
      Priority priority,
      Partner partner,
      String externalEventId,
      Instant slaStartedAt,
      Instant slaDueAt) {
    Ticket ticket =
        new Ticket(
            subject,
            description,
            customerName,
            customerEmail,
            category,
            priority,
            TicketSource.WEBHOOK,
            slaStartedAt,
            slaDueAt);
    ticket.partner = Objects.requireNonNull(partner, "partner");
    ticket.externalEventId = Objects.requireNonNull(externalEventId, "externalEventId");
    return ticket;
  }

  // ---------------------------------------------------------------------------------------
  // Behaviour. Callers have already passed TicketAccessPolicy; these methods guard invariants.
  // ---------------------------------------------------------------------------------------

  public boolean isClosed() {
    return status == TicketStatus.CLOSED;
  }

  /** §5.2: a closed ticket is immutable until reopened. Called first by every mutation. */
  public void ensureNotClosed() {
    if (isClosed()) {
      throw new ApiException(
          ErrorCode.TICKET_CLOSED, "Ticket " + ticketNumber + " is closed. Reopen it first.");
    }
  }

  public boolean isAssignedTo(java.util.UUID agentId) {
    return assignedAgent != null && assignedAgent.getId().equals(agentId);
  }

  /** True when the ticket is still unresolved and its deadline has passed. */
  public boolean isSlaBreachedAt(Instant now) {
    return status.isUnresolved() && slaDueAt.isBefore(now);
  }

  /**
   * Edits the descriptive fields. {@code null} means "leave unchanged". A priority change
   * recalculates the SLA deadline from the same clock start (§5.4).
   *
   * @return true if anything changed
   */
  public boolean edit(
      String subject, String description, Category category, Priority priority, SlaPolicy sla) {
    ensureNotClosed();
    boolean changed = false;
    if (subject != null && !subject.strip().equals(this.subject)) {
      this.subject = subject.strip();
      changed = true;
    }
    if (description != null && !description.strip().equals(this.description)) {
      this.description = description.strip();
      changed = true;
    }
    if (category != null && category != this.category) {
      this.category = category;
      changed = true;
    }
    if (priority != null && priority != this.priority) {
      this.priority = priority;
      this.slaDueAt = sla.dueAt(slaStartedAt, priority);
      changed = true;
    }
    return changed;
  }

  /**
   * Sets or clears the assignee. Only an OPEN ticket can be left unassigned, because work in
   * progress or resolved work must always have an owner.
   */
  public void assignTo(Agent agent) {
    ensureNotClosed();
    if (agent == null) {
      if (status != TicketStatus.OPEN) {
        throw new ApiException(ErrorCode.ASSIGNEE_REQUIRED, "Only OPEN tickets can be unassigned");
      }
    } else if (!agent.isActive()) {
      throw new ApiException(ErrorCode.AGENT_INACTIVE, "Cannot assign to a deactivated agent");
    }
    this.assignedAgent = agent;
  }

  /**
   * The current assignee gives the ticket back, to {@code to} or (when null) to the queue. They are
   * remembered as {@link #releasedBy} so auto-assignment skips them for this ticket (ADR-029).
   */
  public void giveBack(Agent to) {
    Agent from = assignedAgent;
    assignTo(to);
    releasedBy = from;
  }

  /**
   * Moves the ticket to {@code target} and maintains the lifecycle timestamps. Reopening goes
   * through {@link #reopen} instead.
   *
   * @return the previous status
   */
  public TicketStatus transitionTo(TicketStatus target, Instant now) {
    ensureNotClosed();
    TicketStatus from = this.status;
    TicketAction action =
        TicketStateMachine.actionFor(from, target)
            .filter(a -> a != TicketAction.REOPEN)
            .orElseThrow(() ->
                new ApiException(
                    ErrorCode.INVALID_STATUS_TRANSITION,
                    "Cannot move a ticket from " + from + " to " + target));
    switch (action) {
      case START_PROGRESS -> {
        if (assignedAgent == null) {
          throw new ApiException(
              ErrorCode.ASSIGNEE_REQUIRED, "Assign the ticket before starting work on it");
        }
        if (firstResponseAt == null) {
          firstResponseAt = now;
        }
      }
      case RELEASE -> {
        releasedBy = assignedAgent;
        assignedAgent = null;
      }
      case RESOLVE -> resolvedAt = now;
      case RETURN_TO_PROGRESS -> resolvedAt = null;
      case CLOSE -> closedAt = now;
      default -> throw new IllegalStateException("Unexpected transition action " + action);
    }
    this.status = target;
    return from;
  }

  /**
   * CLOSED → OPEN (A2). Keeps the previous assignee unless they've been deactivated (R5), restarts
   * the SLA clock and resets the escalation level, because a new SLA period begins (R4).
   */
  public void reopen(Instant now, SlaPolicy sla) {
    if (!isClosed()) {
      throw new ApiException(
          ErrorCode.INVALID_STATUS_TRANSITION, "Only closed tickets can be reopened");
    }
    this.status = TicketStatus.OPEN;
    this.closedAt = null;
    this.resolvedAt = null;
    this.reopenedCount++;
    if (assignedAgent != null && !assignedAgent.isActive()) {
      this.assignedAgent = null;
    }
    this.slaStartedAt = now;
    this.slaDueAt = sla.dueAt(now, priority);
    this.escalationLevel = 0;
  }

  /** Manual escalation (§5.4). @return the new level */
  public int escalate() {
    ensureNotClosed();
    if (!status.isUnresolved()) {
      throw new ApiException(
          ErrorCode.INVALID_STATUS_TRANSITION, "Only OPEN or IN_PROGRESS tickets can be escalated");
    }
    this.escalationLevel++;
    return escalationLevel;
  }

  /**
   * Automatic escalation by the SLA scheduler: raise to {@code level} if lower. Idempotent, so a
   * scheduler re-run (or a second instance) never escalates twice for the same level.
   *
   * @return true if the level was raised
   */
  public boolean escalateToAtLeast(int level) {
    if (!status.isUnresolved() || escalationLevel >= level) {
      return false;
    }
    this.escalationLevel = (short) level;
    return true;
  }
}
