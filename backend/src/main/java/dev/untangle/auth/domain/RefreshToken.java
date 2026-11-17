package dev.untangle.auth.domain;

import dev.untangle.agent.domain.Agent;
import dev.untangle.common.audit.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/**
 * A refresh token record (table {@code refresh_tokens}). Only the SHA-256 hash is stored, so a
 * database leak does not leak usable tokens.
 *
 * <p>Tokens of one login session share a {@code familyId}. Each refresh rotates the token: the old
 * one is revoked and points to its replacement. Presenting an already-rotated token means it was
 * probably stolen, so the whole family is revoked (Phase 2).
 *
 * <p>{@code @Version} matters here: two concurrent refreshes with the same token race to rotate it.
 * Only one can win, and the loser is treated as a reuse attempt.
 */
@Getter
@Entity
@Table(name = "refresh_tokens")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "agent_id", nullable = false, updatable = false)
  private Agent agent;

  @Column(name = "token_hash", nullable = false, length = 64, updatable = false)
  private String tokenHash;

  @Column(name = "family_id", nullable = false, updatable = false)
  private UUID familyId;

  @Column(name = "expires_at", nullable = false, updatable = false)
  private Instant expiresAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Column(name = "replaced_by_id")
  private UUID replacedById;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "user_agent", length = 255, updatable = false)
  private String userAgent;

  @Column(name = "ip", length = 45, updatable = false)
  private String ip;

  @Version
  @Column(name = "version", nullable = false)
  private Long version;

  @Builder
  private RefreshToken(
      Agent agent,
      String tokenHash,
      UUID familyId,
      Instant expiresAt,
      String userAgent,
      String ip) {
    this.agent = Objects.requireNonNull(agent, "agent");
    this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash");
    this.familyId = Objects.requireNonNull(familyId, "familyId");
    this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    this.userAgent = userAgent;
    this.ip = ip;
  }
}
