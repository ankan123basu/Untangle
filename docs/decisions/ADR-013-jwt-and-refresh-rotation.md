# ADR-013: JWT access tokens via Spring's resource server, with rotating refresh tokens

- **Status:** Accepted
- **Date:** 2026-11-18
- **Phase:** 2

## Context

The backend must be stateless (any instance serves any request), so sessions are out. MASTER_PROMPT
§6.1 asks for a 15-minute access JWT, a 7-day refresh token in an `HttpOnly` cookie, rotation with
reuse detection, and immediate effect of deactivation. It offers two libraries: `jjwt`, or Spring
Security's OAuth2 Resource Server.

## Options considered

1. **jjwt + a hand-written `JwtAuthenticationFilter`.** Full control, but we'd write and maintain our
   own bearer parsing, error handling and filter ordering, which is security-critical code.
2. **Spring Security OAuth2 Resource Server** (`NimbusJwtEncoder`/`NimbusJwtDecoder`, HS256). Ships
   the bearer-token filter, validators and entry-point hooks; we only plug in a converter.
3. **RS256/ES256 asymmetric keys.** Needed when *other* services verify our tokens. Here, only one
   service signs and verifies.

For refresh tokens:

- **(a) Long-lived JWT refresh token.** Can't be revoked individually.
- **(b) Opaque random token, hashed in the DB, rotated on each use, grouped in families.**

## Decision

- **Option 2 with HS256** and a ≥ 256-bit key from `JWT_SECRET`. The decoder accepts only HS256 and
  validates `exp` (30 s skew) and `iss`.
- The token's `sub` is the agent id. On each request `JwtAgentAuthenticationConverter` loads the
  agent (Caffeine cache, 30 s TTL, evicted on change). It rejects inactive agents and derives
  permissions from the **current** role.
- **Refresh: option (b).** 32 random bytes; only their SHA-256 is stored.
  - Each refresh issues a successor in the same family and marks the old token revoked with
    `replaced_by_id`, using a conditional `UPDATE ... WHERE revoked_at IS NULL`.
  - Presenting a token whose rotation already committed means reuse: revoke the whole family and
    return `401 AUTH_REFRESH_REUSED`.
  - A concurrent double-refresh (the loser of the conditional UPDATE) gets `401
    AUTH_REFRESH_INVALID` without revoking anything, because a tab race is not theft.

## Consequences

- **Pros:** no custom crypto or filter code; stateless verification; revocation where it matters
  (refresh); role changes and deactivation apply without waiting for token expiry; a leaked database
  doesn't leak usable refresh tokens.
- **Cons:** one cached DB lookup per agent per 30 s per instance. Other instances honour a
  deactivation only after the cache TTL (documented, R8). Rotating `JWT_SECRET` signs everyone out.
  Moving to RS256 later is a config change in `JwtConfig` if other services ever need to verify
  tokens.
- **Tests:** `AuthIntegrationTest` (21 cases: lockout, rotation, reuse, expired/tampered/foreign-key
  tokens, deactivation, live role change).

## Simple explanation

Signing in gives you a 15-minute pass and a single-use "renewal ticket". Each renewal gives you a
new pass and a new ticket. If an old ticket is ever shown again, we assume it was copied and cancel
the whole session.
