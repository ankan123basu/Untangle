package dev.untangle.agent.infrastructure;

import dev.untangle.agent.domain.Agent;
import dev.untangle.agent.domain.Role;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AgentRepository extends JpaRepository<Agent, UUID> {

  /**
   * Login lookup. Spring Data turns {@code IgnoreCase} into {@code lower(email) = lower(?)}, which
   * matches the {@code ux_agents_email_lower} index exactly.
   */
  Optional<Agent> findByEmailIgnoreCase(String email);

  boolean existsByEmailIgnoreCase(String email);

  /** "Last active admin" safety rule: never deactivate or demote the final one. */
  long countByRoleAndActiveTrue(Role role);

  /** Recipients for SLA-breach and escalation notifications. */
  List<Agent> findAllByRoleAndActiveTrue(Role role);

  /** Assignment dropdowns. */
  List<Agent> findAllByActiveTrueOrderByFullNameAsc();

  /** Admin screen: everyone, active first. */
  List<Agent> findAllByOrderByActiveDescFullNameAsc();

  /**
   * Locks every active admin row ({@code SELECT ... FOR UPDATE}) before a demote/deactivate check.
   * Without the lock, two admins demoting each other at the same moment would both see "2 admins
   * left", both succeed, and leave zero admins.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select a from Agent a where a.role = :role and a.active = true")
  List<Agent> lockActiveByRole(Role role);

  // ---- Login bookkeeping: single atomic UPDATEs, so parallel attempts can't lose a count. ----

  @Modifying
  @Query("update Agent a set a.failedLoginAttempts = a.failedLoginAttempts + 1 where a.id = :id")
  int incrementFailedLoginAttempts(UUID id);

  /** Locks the account if the threshold is reached; returns 1 if it just got locked. */
  @Modifying
  @Query(
      "update Agent a set a.lockedUntil = :until, a.failedLoginAttempts = 0"
          + " where a.id = :id and a.failedLoginAttempts >= :maxAttempts")
  int lockIfAttemptsReached(UUID id, int maxAttempts, Instant until);

  @Modifying
  @Query(
      "update Agent a set a.failedLoginAttempts = 0, a.lockedUntil = null, a.lastLoginAt = :now"
          + " where a.id = :id")
  int recordSuccessfulLogin(UUID id, Instant now);
}
