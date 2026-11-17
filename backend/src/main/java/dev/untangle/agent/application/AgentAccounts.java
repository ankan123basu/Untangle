package dev.untangle.agent.application;

import dev.untangle.agent.domain.Agent;
import dev.untangle.agent.domain.Role;
import dev.untangle.agent.infrastructure.AgentRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Account operations the login flow needs. Each method is its own short transaction, so a failed
 * login's counter increment is committed even though the login itself then fails.
 */
@Service
public class AgentAccounts {

  private final AgentRepository agents;

  public AgentAccounts(AgentRepository agents) {
    this.agents = agents;
  }

  /** Everything login needs, including the password hash. Never leaves the auth flow. */
  public record LoginAccount(
      UUID id,
      String email,
      String fullName,
      Role role,
      String passwordHash,
      boolean active,
      Instant lockedUntil) {

    public boolean isLockedAt(Instant now) {
      return lockedUntil != null && lockedUntil.isAfter(now);
    }

    @Override
    public String toString() {
      return "LoginAccount[id=" + id + ", role=" + role + ", active=" + active + "]";
    }
  }

  @Transactional(readOnly = true)
  public Optional<LoginAccount> findByEmail(String email) {
    return agents
        .findByEmailIgnoreCase(email.strip())
        .map(a ->
            new LoginAccount(
                a.getId(),
                a.getEmail(),
                a.getFullName(),
                a.getRole(),
                a.getPasswordHash(),
                a.isActive(),
                a.getLockedUntil()));
  }

  /**
   * Counts a failed password and locks the account once {@code maxAttempts} is reached.
   *
   * @return {@code true} if this failure locked the account
   */
  @Transactional
  public boolean recordFailedLogin(UUID agentId, int maxAttempts, Instant lockUntil) {
    agents.incrementFailedLoginAttempts(agentId);
    return agents.lockIfAttemptsReached(agentId, maxAttempts, lockUntil) == 1;
  }

  @Transactional
  public void recordSuccessfulLogin(UUID agentId, Instant now) {
    agents.recordSuccessfulLogin(agentId, now);
  }

  /** A lazy reference for foreign keys (no SELECT). */
  @Transactional(readOnly = true)
  public Agent reference(UUID agentId) {
    return agents.getReferenceById(agentId);
  }
}
