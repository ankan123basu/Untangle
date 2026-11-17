package dev.untangle.security.jwt;

import dev.untangle.agent.domain.Role;
import dev.untangle.security.SecurityProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Issues short-lived access tokens (JWT, HS256, 15 minutes by default).
 *
 * <p>Claims: {@code iss}, {@code sub} (agent id), {@code iat}, {@code exp}, {@code jti} (unique id
 * for log correlation) and {@code role} (informational only; authorisation uses the live role, see
 * {@link JwtAgentAuthenticationConverter}). No email or name: JWTs are only base64-encoded, not
 * encrypted, so they must not carry PII.
 */
@Service
public class JwtService {

  private final JwtEncoder encoder;
  private final SecurityProperties.Jwt config;
  private final Clock clock;

  public JwtService(JwtEncoder encoder, SecurityProperties properties, Clock clock) {
    this.encoder = encoder;
    this.config = properties.jwt();
    this.clock = clock;
  }

  public record AccessToken(String value, Instant expiresAt, Duration ttl) {}

  public AccessToken issue(UUID agentId, Role role) {
    Instant now = clock.instant();
    Instant expiresAt = now.plus(config.accessTokenTtl());
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(config.issuer())
            .subject(agentId.toString())
            .issuedAt(now)
            .expiresAt(expiresAt)
            .id(UUID.randomUUID().toString())
            .claim("role", role.name())
            .build();
    JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
    String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    return new AccessToken(token, expiresAt, config.accessTokenTtl());
  }
}
