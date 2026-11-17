package dev.untangle.ai.domain;

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
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * An AI suggestion with its provenance (table {@code ticket_ai_insights}).
 *
 * <p>We keep the model name and prompt version for every suggestion, plus whether the agent
 * accepted it. That lets us measure whether the AI is actually useful, and compare prompt versions
 * (Section 10).
 */
@Getter
@Entity
@Table(name = "ticket_ai_insights")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TicketAiInsight extends BaseEntity {

  /** {@code null} for triage run on the create form, before the ticket exists. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "ticket_id", updatable = false)
  private Ticket ticket;

  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, length = 20, updatable = false)
  private InsightKind kind;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "content", nullable = false, columnDefinition = "jsonb", updatable = false)
  private Map<String, Object> content;

  @Column(name = "model", nullable = false, length = 100, updatable = false)
  private String model;

  @Column(name = "prompt_version", nullable = false, length = 20, updatable = false)
  private String promptVersion;

  @Column(name = "accepted")
  private Boolean accepted;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "created_by_agent_id", updatable = false)
  private Agent createdBy;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Version
  @Column(name = "version", nullable = false)
  private Long version;

  @Builder
  private TicketAiInsight(
      Ticket ticket,
      InsightKind kind,
      Map<String, Object> content,
      String model,
      String promptVersion,
      Agent createdBy) {
    this.ticket = ticket;
    this.kind = Objects.requireNonNull(kind, "kind");
    // Not Map.copyOf: suggestion fields may legitimately be null (e.g. an invalid enum was
    // dropped).
    this.content =
        java.util.Collections.unmodifiableMap(
            new java.util.LinkedHashMap<>(Objects.requireNonNull(content, "content")));
    this.model = Objects.requireNonNull(model, "model");
    this.promptVersion = Objects.requireNonNull(promptVersion, "promptVersion");
    this.createdBy = createdBy;
  }

  /** Agent feedback: was this suggestion useful? */
  public void recordFeedback(boolean accepted) {
    this.accepted = accepted;
  }
}
