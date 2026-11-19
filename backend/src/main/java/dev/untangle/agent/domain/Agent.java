package dev.untangle.agent.domain;

import dev.untangle.common.audit.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A support staff member, who is also a system user (table {@code agents}).
 *
 * <p>Agents are never deleted, only deactivated (A13), so ticket history always points at a real
 * person. Behaviour such as login lockout and role changes is added with the services that need it
 * (Phase 2), keeping invariants inside the entity.
 */
@Getter
@Entity
@Table(name = "agents")
@NoArgsConstructor(access = AccessLevel.PROTECTED) // required by JPA, hidden from application code
public class Agent extends AuditedEntity {

  @Column(name = "full_name", nullable = false, length = 120)
  private String fullName;

  @Column(name = "email", nullable = false, length = 254)
  private String email;

  @Column(name = "password_hash", nullable = false, length = 100)
  private String passwordHash;

  @Enumerated(EnumType.STRING)
  @Column(name = "role", nullable = false, length = 10)
  private Role role;

  @Column(name = "active", nullable = false)
  private boolean active = true;

  @Column(name = "failed_login_attempts", nullable = false)
  private int failedLoginAttempts;

  @Column(name = "locked_until")
  private Instant lockedUntil;

  @Column(name = "last_login_at")
  private Instant lastLoginAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, length = 10)
  private AgentKind kind = AgentKind.HUMAN;

  public Agent(String fullName, String email, String passwordHash, Role role) {
    this.fullName = Objects.requireNonNull(fullName, "fullName").strip();
    this.email = Objects.requireNonNull(email, "email").strip();
    this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash");
    this.role = Objects.requireNonNull(role, "role");
  }

  /**
   * The AI teammate (ADR-028): always a plain AGENT, with a password hash no input can match (not a
   * BCrypt string), and login refuses AI accounts before any password check anyway.
   */
  public static Agent aiTeammate(String fullName, String email) {
    Agent ai = new Agent(fullName, email, "!no-login", Role.AGENT);
    ai.kind = AgentKind.AI;
    return ai;
  }

  /** True for the AI teammate. */
  public boolean isAi() {
    return kind == AgentKind.AI;
  }

  public void deactivate() {
    this.active = false;
  }

  public void activate() {
    this.active = true;
  }

  public void rename(String fullName) {
    this.fullName = Objects.requireNonNull(fullName, "fullName").strip();
  }

  public void changeRole(Role role) {
    if (isAi() && role != Role.AGENT) {
      throw new IllegalStateException("The AI teammate is always a plain agent");
    }
    this.role = Objects.requireNonNull(role, "role");
  }

  /** True while a lockout (5 failed logins) is in force. */
  public boolean isLockedAt(Instant now) {
    return lockedUntil != null && lockedUntil.isAfter(now);
  }
}
