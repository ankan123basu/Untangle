package dev.untangle.auth.application;

import dev.untangle.agent.application.AgentAccounts;
import dev.untangle.auth.domain.RefreshToken;
import dev.untangle.auth.infrastructure.RefreshTokenRepository;
import dev.untangle.common.error.ApiException;
import dev.untangle.common.error.ErrorCode;
import dev.untangle.security.SecurityProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional persistence for refresh tokens. Each method is one short transaction; {@link
 * AuthService} orchestrates them.
 */
@Service
class RefreshTokenStore {

  /** What the auth flow needs to know about a stored token, without exposing the entity. */
  record StoredToken(
      UUID id,
      UUID agentId,
      UUID familyId,
      Instant expiresAt,
      Instant revokedAt,
      UUID replacedById) {

    boolean isRevoked() {
      return revokedAt != null;
    }

    boolean wasRotated() {
      return replacedById != null;
    }
  }

  /** A freshly issued token: the raw value goes to the cookie, only its hash was stored. */
  record IssuedToken(String rawValue, Instant expiresAt, Duration ttl) {}

  private final RefreshTokenRepository tokens;
  private final AgentAccounts agentAccounts;
  private final Duration ttl;
  private final Clock clock;

  RefreshTokenStore(
      RefreshTokenRepository tokens,
      AgentAccounts agentAccounts,
      SecurityProperties properties,
      Clock clock) {
    this.tokens = tokens;
    this.agentAccounts = agentAccounts;
    this.ttl = properties.refresh().ttl();
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  Optional<StoredToken> find(String rawValue) {
    return tokens
        .findByTokenHash(RefreshTokens.sha256Hex(rawValue))
        .map(t ->
            new StoredToken(
                t.getId(),
                t.getAgent().getId(),
                t.getFamilyId(),
                t.getExpiresAt(),
                t.getRevokedAt(),
                t.getReplacedById()));
  }

  /** Starts a new session (token family) at login. */
  @Transactional
  IssuedToken issueNewFamily(UUID agentId, String ip, String userAgent) {
    return issue(agentId, UUID.randomUUID(), ip, userAgent).issued();
  }

  /**
   * Rotates {@code current}: issues its successor in the same family, then marks {@code current} as
   * replaced with a conditional UPDATE. If another request rotated it first, the UPDATE matches no
   * row, and we throw, which rolls back the successor too.
   */
  @Transactional
  IssuedToken rotate(StoredToken current, String ip, String userAgent) {
    Issued next = issue(current.agentId(), current.familyId(), ip, userAgent);
    int updated = tokens.markRotated(current.id(), next.id(), clock.instant());
    if (updated == 0) {
      throw new ApiException(ErrorCode.AUTH_REFRESH_INVALID);
    }
    return next.issued();
  }

  @Transactional
  void revokeFamily(UUID familyId) {
    tokens.revokeFamily(familyId, clock.instant());
  }

  @Transactional
  int revokeAllForAgent(UUID agentId) {
    return tokens.revokeAllForAgent(agentId, clock.instant());
  }

  private record Issued(UUID id, IssuedToken issued) {}

  private Issued issue(UUID agentId, UUID familyId, String ip, String userAgent) {
    Instant now = clock.instant();
    String raw = RefreshTokens.newRawToken();
    RefreshToken token =
        RefreshToken.builder()
            .agent(agentAccounts.reference(agentId))
            .tokenHash(RefreshTokens.sha256Hex(raw))
            .familyId(familyId)
            .expiresAt(now.plus(ttl))
            .ip(ip)
            .userAgent(truncate(userAgent, 255))
            .build();
    tokens.saveAndFlush(token);
    return new Issued(token.getId(), new IssuedToken(raw, now.plus(ttl), ttl));
  }

  private static String truncate(String value, int max) {
    return value == null || value.length() <= max ? value : value.substring(0, max);
  }
}
