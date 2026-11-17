package dev.untangle.webhook.domain;

import dev.untangle.common.audit.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * An external company allowed to create tickets through the signed webhook (table {@code
 * partners}).
 *
 * <p>The HMAC secret is stored <b>encrypted</b> (AES-256-GCM), never in plain text and never
 * hashed: we must recover it to recompute signatures. During a rotation the previous secret stays
 * valid until {@link #previousSecretExpiresAt}. Encryption and rotation logic arrive in Phase 4.
 */
@Getter
@Entity
@Table(name = "partners")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Partner extends AuditedEntity {

  @Column(name = "code", nullable = false, length = 64, updatable = false)
  private String code;

  @Column(name = "name", nullable = false, length = 120)
  private String name;

  @Column(name = "secret_ciphertext", nullable = false)
  private String secretCiphertext;

  @Column(name = "previous_secret_ciphertext")
  private String previousSecretCiphertext;

  @Column(name = "previous_secret_expires_at")
  private Instant previousSecretExpiresAt;

  @Column(name = "active", nullable = false)
  private boolean active = true;

  @Column(name = "rate_limit_per_minute", nullable = false)
  private int rateLimitPerMinute = 60;

  public Partner(String code, String name, String secretCiphertext) {
    this.code = Objects.requireNonNull(code, "code");
    this.name = Objects.requireNonNull(name, "name");
    this.secretCiphertext = Objects.requireNonNull(secretCiphertext, "secretCiphertext");
  }

  public Partner(String code, String name, String secretCiphertext, int rateLimitPerMinute) {
    this(code, name, secretCiphertext);
    this.rateLimitPerMinute = rateLimitPerMinute;
  }

  /**
   * Rotation (§7.3): the new secret becomes current; the old one stays valid until {@code
   * previousValidUntil} so the partner can switch over without dropped webhooks. Rotating again
   * inside the grace window retires the older previous secret immediately.
   */
  public void rotateSecret(String newCiphertext, Instant previousValidUntil) {
    this.previousSecretCiphertext = this.secretCiphertext;
    this.previousSecretExpiresAt = Objects.requireNonNull(previousValidUntil, "previousValidUntil");
    this.secretCiphertext = Objects.requireNonNull(newCiphertext, "newCiphertext");
  }

  /** Replaces the secret with no grace period (demo bootstrap only). */
  public void replaceSecret(String newCiphertext) {
    this.secretCiphertext = Objects.requireNonNull(newCiphertext, "newCiphertext");
    this.previousSecretCiphertext = null;
    this.previousSecretExpiresAt = null;
  }

  /** True while the previous secret is still inside its rotation grace window. */
  public boolean hasPreviousSecretValidAt(Instant now) {
    return previousSecretCiphertext != null
        && previousSecretExpiresAt != null
        && previousSecretExpiresAt.isAfter(now);
  }

  public void rename(String name) {
    this.name = Objects.requireNonNull(name, "name").strip();
  }

  public void changeRateLimit(int perMinute) {
    this.rateLimitPerMinute = perMinute;
  }

  public void setActive(boolean active) {
    this.active = active;
  }
}
