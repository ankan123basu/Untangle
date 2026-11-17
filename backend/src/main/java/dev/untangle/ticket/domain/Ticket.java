package dev.untangle.ticket.domain;

import dev.untangle.agent.domain.Agent;
import dev.untangle.common.audit.AuditedEntity;
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
 * can never be half-initialised (for example a webhook ticket without its event id). State changes
 * (assign, transition, reopen, escalate) are added as methods in Phase 3, guarded by the state
 * machine and the closed-ticket rule. There are deliberately no public setters.
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

  @Column(name = "sla_due_at", nullable = false)
  private Instant slaDueAt;

  @Column(name = "first_response_at")
  private Instant firstResponseAt;

  @Column(name = "resolved_at")
  private Instant resolvedAt;

  @Column(name = "closed_at")
  private Instant closedAt;

  @Column(name = "reopened_count", nullable = false)
  private int reopenedCount;

  private Ticket(
      String subject,
      String description,
      String customerName,
      String customerEmail,
      Category category,
      Priority priority,
      TicketSource source,
      Instant slaDueAt) {
    this.subject = Objects.requireNonNull(subject, "subject").strip();
    this.description = Objects.requireNonNull(description, "description").strip();
    this.customerName = Objects.requireNonNull(customerName, "customerName").strip();
    this.customerEmail = customerEmail == null ? null : customerEmail.strip();
    this.category = Objects.requireNonNull(category, "category");
    this.priority = Objects.requireNonNull(priority, "priority");
    this.source = source;
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
            slaDueAt);
    ticket.partner = Objects.requireNonNull(partner, "partner");
    ticket.externalEventId = Objects.requireNonNull(externalEventId, "externalEventId");
    return ticket;
  }
}
