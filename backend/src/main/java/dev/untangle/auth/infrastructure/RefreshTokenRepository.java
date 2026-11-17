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

  /**
   * Rotation as one atomic, conditional UPDATE: it only succeeds if the token is still live. When
   * two requests rotate the same token at once, exactly one gets {@code 1} back; the other gets
   * {@code 0} and is rejected. No read-then-write race.
   */
  @Modifying
  @Query(
      "update RefreshToken t set t.revokedAt = :now, t.replacedById = :replacementId,"
          + " t.version = t.version + 1 where t.id = :id and t.revokedAt is null")
  int markRotated(UUID id, UUID replacementId, Instant now);

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
