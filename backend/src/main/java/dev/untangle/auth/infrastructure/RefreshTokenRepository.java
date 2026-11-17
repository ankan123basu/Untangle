package dev.untangle.auth.infrastructure;

import dev.untangle.auth.domain.RefreshToken;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

  Optional<RefreshToken> findByTokenHash(String tokenHash);

  /** Reuse detection and logout: revoke every live token of a family in one statement. */
  @Modifying
  @Query(
      "update RefreshToken t set t.revokedAt = :now, t.version = t.version + 1"
          + " where t.familyId = :familyId and t.revokedAt is null")
  int revokeFamily(UUID familyId, Instant now);

  /** Agent deactivation: revoke all of their live sessions. */
  @Modifying
  @Query(
      "update RefreshToken t set t.revokedAt = :now, t.version = t.version + 1"
          + " where t.agent.id = :agentId and t.revokedAt is null")
  int revokeAllForAgent(UUID agentId, Instant now);
}
