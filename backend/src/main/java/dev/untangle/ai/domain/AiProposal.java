package dev.untangle.ai.domain;

import dev.untangle.agent.domain.Agent;
import dev.untangle.common.audit.AuditedEntity;
import dev.untangle.ticket.domain.Ticket;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A resolution proposed by the AI teammate (table {@code ai_proposals}, ADR-028). A human lead
 * approves it (the ticket is resolved with this note) or rejects it by taking the ticket over.
 * Model and prompt version are kept for provenance.
 */
@Getter
@Entity
@Table(name = "ai_proposals")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiProposal extends AuditedEntity {

  public enum Status {
    PENDING,
    APPROVED,
    REJECTED,
    SUPERSEDED
  }

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "ticket_id", nullable = false, updatable = false)
  private Ticket ticket;

  @Column(name = "reply", nullable = false, length = 5000, updatable = false)
  private String reply;

  @Column(name = "resolution_note", nullable = false, length = 5000, updatable = false)
  private String resolutionNote;

  @Column(name = "confidence", nullable = false, precision = 3, scale = 2, updatable = false)
  private BigDecimal confidence;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 12)
  private Status status = Status.PENDING;

  @Column(name = "model", nullable = false, length = 100, updatable = false)
  private String model;

  @Column(name = "prompt_version", nullable = false, length = 40, updatable = false)
  private String promptVersion;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "decided_by_agent_id")
  private Agent decidedBy;

  @Column(name = "decided_at")
  private Instant decidedAt;

  public AiProposal(
      Ticket ticket,
      String reply,
      String resolutionNote,
      double confidence,
      String model,
      String promptVersion) {
    this.ticket = Objects.requireNonNull(ticket, "ticket");
    this.reply = Objects.requireNonNull(reply, "reply");
    this.resolutionNote = Objects.requireNonNull(resolutionNote, "resolutionNote");
    this.confidence =
        BigDecimal.valueOf(Math.max(0, Math.min(1, confidence))).setScale(2, RoundingMode.HALF_UP);
    this.model = Objects.requireNonNull(model, "model");
    this.promptVersion = Objects.requireNonNull(promptVersion, "promptVersion");
  }

  public boolean isPending() {
    return status == Status.PENDING;
  }

  public void approve(Agent by, Instant at) {
    decide(Status.APPROVED, by, at);
  }

  public void reject(Agent by, Instant at) {
    decide(Status.REJECTED, by, at);
  }

  /** The ticket moved on without a decision (reassigned, resolved by hand, handed back). */
  public void supersede() {
    if (isPending()) {
      status = Status.SUPERSEDED;
    }
  }

  private void decide(Status outcome, Agent by, Instant at) {
    if (!isPending()) {
      throw new IllegalStateException("Proposal already " + status);
    }
    this.status = outcome;
    this.decidedBy = Objects.requireNonNull(by, "by");
    this.decidedAt = Objects.requireNonNull(at, "at");
  }
}
