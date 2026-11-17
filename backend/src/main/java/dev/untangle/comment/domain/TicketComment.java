package dev.untangle.comment.domain;

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
import java.util.Map;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One entry on a ticket's timeline (table {@code ticket_comments}).
 *
 * <p>The timeline is the ticket's <b>audit trail</b>, so entries are append-only (ADR-010):
 * {@code @Immutable} stops Hibernate from ever issuing an UPDATE, and a database trigger rejects
 * UPDATEs from anywhere else.
 *
 * <p>{@code metadata} is JSONB for structured facts about system events, e.g. {@code {"from":
 * "OPEN", "to": "IN_PROGRESS", "actorId": "..."}}. The UI uses it to render rich timeline items
 * without parsing the text body.
 */
@Getter
@Entity
@Immutable
@Table(name = "ticket_comments")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TicketComment extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "ticket_id", nullable = false, updatable = false)
  private Ticket ticket;

  /** {@code null} means the system wrote it. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "author_agent_id", updatable = false)
  private Agent author;

  @Enumerated(EnumType.STRING)
  @Column(name = "type", nullable = false, length = 20, updatable = false)
  private CommentType type;

  @Column(name = "body", nullable = false, length = 5000, updatable = false)
  private String body;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "metadata", columnDefinition = "jsonb", updatable = false)
  private Map<String, Object> metadata;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  private TicketComment(
      Ticket ticket, Agent author, CommentType type, String body, Map<String, Object> metadata) {
    this.ticket = Objects.requireNonNull(ticket, "ticket");
    this.author = author;
    this.type = Objects.requireNonNull(type, "type");
    this.body = Objects.requireNonNull(body, "body");
    this.metadata = metadata == null ? null : Map.copyOf(metadata);
  }

  /** A comment or resolution note written by a person. */
  public static TicketComment byAgent(Ticket ticket, Agent author, CommentType type, String body) {
    if (type == CommentType.SYSTEM) {
      throw new IllegalArgumentException("SYSTEM entries are created with TicketComment.system()");
    }
    return new TicketComment(ticket, Objects.requireNonNull(author, "author"), type, body, null);
  }

  /**
   * A system event. {@code actor} is the person who triggered it (e.g. a lead closing a ticket), or
   * {@code null} for automatic events such as an SLA breach.
   */
  public static TicketComment system(
      Ticket ticket, Agent actor, String body, Map<String, Object> metadata) {
    return new TicketComment(ticket, actor, CommentType.SYSTEM, body, metadata);
  }
}
