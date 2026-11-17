package dev.untangle.security;

import dev.untangle.common.ratelimit.RateLimitPolicy;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed {@code app.security.*} configuration (see application.yml for defaults and env vars).
 *
 * <p><b>Fail fast</b> (Section 6.3): the constructor validates secrets, so a missing or weak {@code
 * JWT_SECRET} stops the application at startup with a clear message, instead of failing on the
 * first login (or, worse, running with a guessable key).
 */
@ConfigurationProperties("app.security")
public record SecurityProperties(
    Jwt jwt,
    Refresh refresh,
    Lockout lockout,
    int bcryptStrength,
    Duration principalCacheTtl,
    List<String> corsAllowedOrigins,
    RateLimits rateLimits) {

  /** HS256 keys must be at least 256 bits (RFC 7518 §3.2). */
  public static final int MIN_JWT_SECRET_BYTES = 32;

  public SecurityProperties {
    Objects.requireNonNull(jwt, "app.security.jwt");
    Objects.requireNonNull(refresh, "app.security.refresh");
    Objects.requireNonNull(lockout, "app.security.lockout");
    Objects.requireNonNull(rateLimits, "app.security.rate-limits");
    if (bcryptStrength < 4 || bcryptStrength > 16) {
      throw new IllegalStateException("app.security.bcrypt-strength must be between 4 and 16");
    }
    principalCacheTtl = principalCacheTtl == null ? Duration.ofSeconds(30) : principalCacheTtl;
    corsAllowedOrigins =
        corsAllowedOrigins == null
            ? List.of()
            : corsAllowedOrigins.stream().filter(o -> o != null && !o.isBlank()).toList();
  }

  public record Jwt(String secret, String issuer, Duration accessTokenTtl) {
    public Jwt {
      if (secret == null || secret.isBlank()) {
        throw new IllegalStateException(
            "JWT_SECRET is not set. Generate one with: openssl rand -base64 48"
                + " (make up does this automatically in .env)");
      }
      byte[] key;
      try {
        key = Base64.getDecoder().decode(secret.strip());
      } catch (IllegalArgumentException e) {
        throw new IllegalStateException("JWT_SECRET must be base64-encoded", e);
      }
      if (key.length < MIN_JWT_SECRET_BYTES) {
        throw new IllegalStateException(
            "JWT_SECRET is too short: "
                + key.length
                + " bytes, need at least "
                + MIN_JWT_SECRET_BYTES);
      }
      issuer = issuer == null || issuer.isBlank() ? "untangle" : issuer;
      accessTokenTtl = accessTokenTtl == null ? Duration.ofMinutes(15) : accessTokenTtl;
    }

    public byte[] secretBytes() {
      return Base64.getDecoder().decode(secret.strip());
    }

    @Override
    public String toString() {
      return "Jwt[secret=<redacted>, issuer=" + issuer + ", accessTokenTtl=" + accessTokenTtl + "]";
    }
  }

  public record Refresh(Duration ttl, boolean cookieSecure) {
    public Refresh {
      ttl = ttl == null ? Duration.ofDays(7) : ttl;
    }
  }

  public record Lockout(int maxFailedAttempts, Duration duration) {
    public Lockout {
      if (maxFailedAttempts < 1) {
        throw new IllegalStateException("app.security.lockout.max-failed-attempts must be >= 1");
      }
      duration = duration == null ? Duration.ofMinutes(15) : duration;
    }
  }

  public record RateLimits(RateLimitPolicy loginPerIp, RateLimitPolicy loginPerEmail) {
    public RateLimits {
      Objects.requireNonNull(loginPerIp, "app.security.rate-limits.login-per-ip");
      Objects.requireNonNull(loginPerEmail, "app.security.rate-limits.login-per-email");
    }
  }
}
