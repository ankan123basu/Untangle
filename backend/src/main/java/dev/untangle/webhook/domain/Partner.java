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
}
