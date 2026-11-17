package dev.untangle.notification.domain;

import dev.untangle.agent.domain.Agent;
import dev.untangle.common.audit.BaseEntity;
import dev.untangle.ticket.domain.Ticket;
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
import org.hibernate.annotations.CreationTimestamp;

/**
 * An in-app alert for one agent (table {@code notifications}). The only mutable field is {@code
 * readAt}, and marking as read is idempotent, so no optimistic-lock version is needed.
 */
@Getter
@Entity
@Table(name = "notifications")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "recipient_agent_id", nullable = false, updatable = false)
  private Agent recipient;

  @Enumerated(EnumType.STRING)
  @Column(name = "type", nullable = false, length = 20, updatable = false)
  private NotificationType type;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "ticket_id", nullable = false, updatable = false)
  private Ticket ticket;

  @Column(name = "message", nullable = false, length = 500, updatable = false)
  private String message;

  @Column(name = "read_at")
  private Instant readAt;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public Notification(Agent recipient, NotificationType type, Ticket ticket, String message) {
    this.recipient = Objects.requireNonNull(recipient, "recipient");
    this.type = Objects.requireNonNull(type, "type");
    this.ticket = Objects.requireNonNull(ticket, "ticket");
    this.message = Objects.requireNonNull(message, "message");
  }

  /** Idempotent: marking an already-read notification keeps the original read time. */
  public void markRead(Instant now) {
    if (readAt == null) {
      readAt = now;
    }
  }
}
