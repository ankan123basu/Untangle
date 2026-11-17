package dev.untangle.auth.application;

import dev.untangle.agent.application.AgentAccounts;
import dev.untangle.agent.application.AgentAccounts.LoginAccount;
import dev.untangle.agent.application.AgentDirectory;
import dev.untangle.agent.application.AgentSnapshot;
import dev.untangle.agent.domain.Role;
import dev.untangle.auth.application.AuthViews.CurrentAgent;
import dev.untangle.auth.application.AuthViews.TokenResponse;
import dev.untangle.auth.application.RefreshTokenStore.IssuedToken;
import dev.untangle.auth.application.RefreshTokenStore.StoredToken;
import dev.untangle.common.error.ApiException;
import dev.untangle.common.error.ErrorCode;
import dev.untangle.common.ratelimit.RateLimiter;
import dev.untangle.common.util.Masking;
import dev.untangle.security.SecurityProperties;
import dev.untangle.security.jwt.JwtService;
import dev.untangle.security.jwt.JwtService.AccessToken;
import dev.untangle.security.rbac.RolePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Login, refresh and logout (MASTER_PROMPT §6.1). See docs/security.md for the full flow diagram.
 *
 * <p>Deliberately <b>not</b> one big transaction: some writes must survive a failed request. A
 * wrong password still counts towards lockout, and a detected token reuse still revokes the family.
 * Each step below is its own short transaction.
 */
@Service
public class AuthService {

  private static final Logger log = LoggerFactory.getLogger(AuthService.class);

  /** Outcome of login/refresh: the JSON body plus the refresh token for the cookie. */
  public record AuthResult(TokenResponse body, String refreshTokenValue, Duration refreshTtl) {

    static AuthResult of(TokenResponse body, IssuedToken refresh) {
      return new AuthResult(body, refresh.rawValue(), refresh.ttl());
    }

    @Override
    public String toString() {
      return "AuthResult[agent=" + body.agent().id() + ", refreshToken=<redacted>]";
    }
  }

  private final AgentAccounts agentAccounts;
  private final AgentDirectory agentDirectory;
  private final RefreshTokenStore refreshTokens;
  private final JwtService jwtService;
  private final RolePermissions rolePermissions;
  private final PasswordEncoder passwordEncoder;
  private final RateLimiter rateLimiter;
  private final SecurityProperties properties;
  private final Clock clock;

  /**
   * A real BCrypt hash of a throwaway value. For unknown emails we still run a full BCrypt check
   * against it, so "no such user" takes as long as "wrong password" and timing doesn't reveal which
   * emails exist.
   */
  private final String timingEqualizerHash;

  public AuthService(
      AgentAccounts agentAccounts,
      AgentDirectory agentDirectory,
      RefreshTokenStore refreshTokens,
      JwtService jwtService,
      RolePermissions rolePermissions,
      PasswordEncoder passwordEncoder,
      RateLimiter rateLimiter,
      SecurityProperties properties,
      Clock clock) {
    this.agentAccounts = agentAccounts;
    this.agentDirectory = agentDirectory;
    this.refreshTokens = refreshTokens;
    this.jwtService = jwtService;
    this.rolePermissions = rolePermissions;
    this.passwordEncoder = passwordEncoder;
    this.rateLimiter = rateLimiter;
    this.properties = properties;
    this.clock = clock;
    this.timingEqualizerHash = passwordEncoder.encode(UUID.randomUUID().toString());
  }

  // ---------------------------------------------------------------------------------------
  // Login
  // ---------------------------------------------------------------------------------------

  public AuthResult login(String email, String password, String ip, String userAgent) {
    String normalizedEmail = email.strip().toLowerCase(Locale.ROOT);
    // 1. Rate limits first: cheap, and stop password spraying before any BCrypt work.
    rateLimiter.consume("login-ip", properties.rateLimits().loginPerIp(), ip);
    rateLimiter.consume("login-email", properties.rateLimits().loginPerEmail(), normalizedEmail);

    Instant now = clock.instant();
    Optional<LoginAccount> found = agentAccounts.findByEmail(normalizedEmail);
    if (found.isEmpty()) {
      passwordEncoder.matches(password, timingEqualizerHash);
      log.info("Login failed: unknown email={}", Masking.email(normalizedEmail));
      throw new ApiException(ErrorCode.AUTH_INVALID_CREDENTIALS);
    }
    LoginAccount account = found.get();

    // 2. Locked accounts are rejected without checking the password (and without counting).
    if (account.isLockedAt(now)) {
      throw locked(account.lockedUntil());
    }

    // 3. Password check. A failure is counted in its own committed transaction.
    if (!passwordEncoder.matches(password, account.passwordHash())) {
      Instant lockUntil = now.plus(properties.lockout().duration());
      boolean nowLocked =
          agentAccounts.recordFailedLogin(
              account.id(), properties.lockout().maxFailedAttempts(), lockUntil);
      log.warn("Login failed: wrong password agentId={} locked={}", account.id(), nowLocked);
      throw nowLocked ? locked(lockUntil) : new ApiException(ErrorCode.AUTH_INVALID_CREDENTIALS);
    }

    // 4. Correct password but deactivated (A13).
    if (!account.active()) {
      log.warn("Login rejected: deactivated agentId={}", account.id());
      throw new ApiException(ErrorCode.AUTH_ACCOUNT_DISABLED);
    }

    agentAccounts.recordSuccessfulLogin(account.id(), now);
    IssuedToken refresh = refreshTokens.issueNewFamily(account.id(), ip, userAgent);
    log.info("Login succeeded agentId={} role={}", account.id(), account.role());
    return AuthResult.of(
        tokenResponse(account.id(), account.fullName(), account.email(), account.role()), refresh);
  }

  // ---------------------------------------------------------------------------------------
  // Refresh (rotation + reuse detection)
  // ---------------------------------------------------------------------------------------

  public AuthResult refresh(String rawRefreshToken, String ip, String userAgent) {
    if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
      throw new ApiException(ErrorCode.AUTH_REFRESH_INVALID);
    }
    StoredToken token =
        refreshTokens
            .find(rawRefreshToken)
            .orElseThrow(() -> new ApiException(ErrorCode.AUTH_REFRESH_INVALID));

    if (token.isRevoked()) {
      if (token.wasRotated()) {
        // A token we already replaced came back. Either an attacker stole it, or the legitimate
        // user's copy was stolen and used first. We can't tell which, so kill the whole session.
        refreshTokens.revokeFamily(token.familyId());
        log.warn(
            "Refresh token REUSE detected agentId={} family={}; session revoked",
            token.agentId(),
            token.familyId());
        throw new ApiException(ErrorCode.AUTH_REFRESH_REUSED);
      }
      throw new ApiException(ErrorCode.AUTH_REFRESH_INVALID); // logged out
    }
    if (!token.expiresAt().isAfter(clock.instant())) {
      throw new ApiException(ErrorCode.AUTH_REFRESH_INVALID);
    }

    AgentSnapshot agent =
        agentDirectory
            .findFresh(token.agentId())
            .orElseThrow(() -> new ApiException(ErrorCode.AUTH_REFRESH_INVALID));
    if (!agent.active()) {
      refreshTokens.revokeFamily(token.familyId());
      throw new ApiException(ErrorCode.AUTH_ACCOUNT_DISABLED);
    }

    IssuedToken next = refreshTokens.rotate(token, ip, userAgent);
    return AuthResult.of(
        tokenResponse(agent.id(), agent.fullName(), agent.email(), agent.role()), next);
  }

  // ---------------------------------------------------------------------------------------
  // Logout and current user
  // ---------------------------------------------------------------------------------------

  /** Revokes the whole session family. Idempotent: logging out twice is fine. */
  public void logout(String rawRefreshToken) {
    if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
      return;
    }
    refreshTokens
        .find(rawRefreshToken)
        .ifPresent(token -> {
          refreshTokens.revokeFamily(token.familyId());
          log.info("Logout agentId={}", token.agentId());
        });
  }

  public CurrentAgent currentAgent(UUID id, String fullName, String email, Role role) {
    return new CurrentAgent(id, fullName, email, role, new TreeSet<>(rolePermissions.of(role)));
  }

  /** Revokes every session of an agent (used when they are deactivated). */
  public int revokeAllSessions(UUID agentId) {
    return refreshTokens.revokeAllForAgent(agentId);
  }

  private TokenResponse tokenResponse(UUID id, String fullName, String email, Role role) {
    AccessToken access = jwtService.issue(id, role);
    return new TokenResponse(
        access.value(),
        "Bearer",
        access.ttl().toSeconds(),
        access.expiresAt(),
        currentAgent(id, fullName, email, role));
  }

  private static ApiException locked(Instant until) {
    return new ApiException(
            ErrorCode.AUTH_ACCOUNT_LOCKED,
            "Too many failed attempts. Try again later or ask an admin.")
        .with("lockedUntil", until);
  }
}
