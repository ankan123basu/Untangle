# Security

## Simple explanation

- **Signing in:** an agent signs in with email and password and gets a short-lived **access token**
  (valid 15 minutes) plus a **refresh token** stored in a locked-down browser cookie. The access
  token goes with every request; when it expires, the app quietly swaps the refresh token for a new
  pair.
- **Stolen refresh tokens are caught:** every refresh token works exactly once. If an old one is
  ever used again, someone must have copied it, so the whole session is shut down and the person has
  to sign in again.
- **Guessing passwords doesn't work:** after 5 wrong passwords the account locks for 15 minutes, and
  each IP address and each email can only try a limited number of times per minute.
- **Who can do what** is decided by role (Agent, Lead, Admin) on the server. The screen hides buttons
  you can't use, but the server is what actually enforces it.
- **Deactivating someone takes effect immediately.** Their current token stops working and their
  sessions are revoked.
- **Partner webhooks** are signed: each request carries a fingerprint made from the message, the
  time and a secret only that partner has. Partner secrets are stored locked with a separate key, so a
  copy of the database alone doesn't reveal them.

## Technical details

### Threat model (STRIDE-lite)

| Threat | Example | Mitigation | Where |
|---|---|---|---|
| **S**poofing | Forged JWT, `alg: none`, other-key token | HS256 only, 256-bit+ key, issuer + expiry validation | `JwtConfig` |
| | Password guessing / spraying | BCrypt cost 12; lockout 5 attempts / 15 min; per-IP (20/min) and per-email (10/min) token buckets; unknown emails take the same BCrypt time | `AuthService`, `RateLimiter` |
| | Stolen refresh token | Rotation on every use; reuse ⇒ whole family revoked (`AUTH_REFRESH_REUSED`) | `AuthService.refresh` |
| | Partner impersonation | HMAC-SHA256 over `timestamp.rawBody`, constant-time compare, per-partner secret (ADR-017) | `WebhookService`, `HmacSignatures` |
| | Webhook replay | ±300 s timestamp window inside the signature + event-id idempotency | `WebhookService` |
| **I**nformation disclosure | Partner secrets in a DB dump | AES-256-GCM with env key, AAD = partner code (ADR-016) | `SecretCipher` |
| | Probing webhook auth | identical generic 401; reasons only in the admin delivery log (ADR-018) | `WebhookService` |
| **D**enial of service | Webhook floods / huge bodies | 64 KB cap before buffering; per-IP then per-partner token buckets | `WebhookBodyLimitFilter`, `RateLimiter` |
| **T**ampering | Edited JWT payload | Signature check | Nimbus decoder |
| | Rewriting the ticket history | Append-only comments (trigger + `@Immutable`) | ADR-010 |
| | Lost updates | Optimistic locking | ADR-011 |
| **R**epudiation | "I didn't close that ticket" | SYSTEM timeline entries with actor; JSON logs with `requestId` | Phase 3, `RequestIdFilter` |
| **I**nformation disclosure | Stack traces, SQL in errors | Global Problem Details handler; `server.error.include-*: never`; rejected values never echoed | `GlobalExceptionHandler` |
| | Account enumeration | Identical 401 body and similar timing for unknown email vs wrong password | `AuthService` |
| | Token theft via XSS | Access token in memory only (Phase 7); refresh token `HttpOnly` | `AuthController` |
| | PII in logs | Emails masked (`r***@example.com`); tokens, passwords and secrets never logged; `toString()` redacts | `Masking`, records |
| | PII in JWT | JWT carries only ids and role, no email or name | `JwtService` |
| **D**enial of service | Login flood | Token buckets (bounded Caffeine maps), 429 + `Retry-After` | `RateLimiter` |
| **E**levation of privilege | Agent calls admin API | URL rules (before body parsing) + `@PreAuthorize` per method; full matrix test | `SecurityConfig`, controllers |
| | Demoted user keeps old powers | Permissions come from the **current** DB role (≤ 30 s cache), not from the token | `JwtAgentAuthenticationConverter` |
| | Zero admins left | Last-admin rule with `SELECT ... FOR UPDATE` on admin rows | `AgentService` |

### Authentication flow

```mermaid
sequenceDiagram
    autonumber
    participant B as Browser (SPA)
    participant N as nginx
    participant A as AuthController / AuthService
    participant DB as PostgreSQL

    B->>N: POST /api/v1/auth/login {email, password}
    N->>A: proxy (X-Forwarded-For, X-Request-Id)
    A->>A: rate limit (IP, email)
    A->>DB: find agent by lower(email)
    A->>A: locked? BCrypt matches? active?
    A->>DB: reset failures, insert refresh_token(hash, new family)
    A-->>B: 200 {accessToken (15 min), agent, permissions}<br/>Set-Cookie: untangle_refresh=… HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth

    Note over B: keeps accessToken in memory only

    B->>A: GET /api/v1/... Authorization: Bearer <JWT>
    A->>A: verify signature/exp/iss → load agent (cache ≤ 30 s) → active? → permissions

    Note over B: on 401 AUTH_TOKEN_EXPIRED (single-flight in Axios)
    B->>A: POST /api/v1/auth/refresh (cookie sent automatically)
    A->>DB: find by SHA-256(token)
    alt token already rotated (reuse)
        A->>DB: revoke whole family
        A-->>B: 401 AUTH_REFRESH_REUSED → sign in again
    else live
        A->>DB: insert successor; UPDATE old SET revoked, replaced_by WHERE revoked_at IS NULL
        A-->>B: 200 new accessToken + new cookie
    end
```

### Decisions and their reasons

| Topic | Choice | Why |
|---|---|---|
| JWT library | Spring Security OAuth2 Resource Server (Nimbus), HS256 | First-party, standard bearer filter, nothing hand-rolled; [ADR-013](decisions/ADR-013-jwt-and-refresh-rotation.md) |
| Access token lifetime | 15 min, memory only | Short exposure if leaked; can't be stolen from storage |
| Refresh token | 256-bit random, SHA-256 stored, 7 days, rotated, family-revoked on reuse | [ADR-013](decisions/ADR-013-jwt-and-refresh-rotation.md) |
| Concurrent refresh of one token | Conditional `UPDATE ... WHERE revoked_at IS NULL`; loser gets `AUTH_REFRESH_INVALID` (not "reuse") | A tab race is not theft; only a token presented *after* its rotation committed counts as reuse |
| Passwords | BCrypt cost 12, 10–72 chars | 72 = BCrypt's input limit; tests use cost 4 |
| Lockout | 5 failures → 15 min, atomic SQL counters | Parallel guesses can't lose a count |
| Locked response | `423 AUTH_ACCOUNT_LOCKED` with `lockedUntil` | Brief requires a distinct UI state; it reveals that the account exists only after 5 wrong passwords |
| Authorisation | Permissions mapped from roles, server-side; URL rules + `@PreAuthorize` | [ADR-014](decisions/ADR-014-rbac-via-permissions.md) |
| CSRF | Disabled | Bearer header isn't auto-sent; the refresh cookie is `SameSite=Strict` and path-scoped |
| CORS | Off unless `CORS_ALLOWED_ORIGINS` set | Same-origin via nginx/Vite proxy |
| Client IP | `server.forward-headers-strategy: native` | Trusts `X-Forwarded-For` only from private/proxy ranges |

### RBAC matrix

Source of truth: `RolePermissions` (tested cell by cell in `RolePermissionsTest`; endpoints in
`EndpointAuthorizationMatrixTest`).

| Permission | AGENT | LEAD | ADMIN |
|---|:-:|:-:|:-:|
| `TICKET_VIEW`, `DASHBOARD_VIEW`, `TICKET_CREATE` | ✅ | ✅ | ✅ |
| `TICKET_SELF_ASSIGN` | ✅ | ✅ | ✅ |
| `TICKET_ASSIGN_ANY` | ❌ | ✅ | ✅ |
| `TICKET_TRANSITION_OWN` | ✅ | ✅ | ✅ |
| `TICKET_TRANSITION_ANY` | ❌ | ✅ | ✅ |
| `TICKET_CLOSE`, `TICKET_REOPEN` | ❌ | ✅ | ✅ |
| `TICKET_CLOSE_OWN_RESOLVED` | config `app.tickets.agent-can-close-own-resolved` (default ❌) | ✅ | ✅ |
| `TICKET_COMMENT`, `TICKET_ESCALATE`, `AI_USE` | ✅ | ✅ | ✅ |
| `TICKET_EDIT_OWN` | ✅ | ✅ | ✅ |
| `TICKET_EDIT_ANY`, `AGENT_WORKLOAD_VIEW` | ❌ | ✅ | ✅ |
| `AGENT_MANAGE`, `PARTNER_MANAGE`, `WEBHOOK_LOG_VIEW` | ❌ | ❌ | ✅ |

*Own* permissions also need an ownership check ("am I the assignee?") from `TicketAccessPolicy`
(37 role × ownership × status cases in `TicketAccessPolicyTest`).

Verified live as well: `make check-features` tries every role against every protected action on
the running stack and prints the matrix (all cells as expected; anonymous calls get 401).

### Deactivation timeline

1. Admin sends `PATCH /agents/{id}/active {false}`. The service checks it's not your own account,
   not the last admin, and that the agent has no open tickets; then it commits.
2. After commit, an `AgentAccessChangedEvent` triggers two things: the cached principal is evicted,
   so the **next request is rejected** (`401 AUTH_ACCOUNT_DISABLED`), and all refresh tokens are
   revoked.
3. Other backend instances (when scaled out) pick it up within `principal-cache-ttl` (30 s) (R8).

### Secrets

| Secret | Source | Rules |
|---|---|---|
| `JWT_SECRET` | env (generated by `make .env`) | base64, ≥ 32 bytes; app refuses to start otherwise; rotating it signs everyone out |
| `POSTGRES_PASSWORD` | env | |
| `BOOTSTRAP_ADMIN_PASSWORD` | env, optional | ≥ 10 chars; used once, only if no admin exists |
| `APP_SECRET_ENCRYPTION_KEY` | env (generated by `make .env`) | base64, exactly 32 bytes; app refuses to start otherwise; losing it means rotating every partner secret |
| Partner HMAC secrets | AES-256-GCM in DB (`v1:iv:ct`) | generated server-side (256 bits), shown once, 24 h rotation grace |

Never committed: `.env` is git-ignored; config records redact secrets in `toString()`.

### AI (Phase 6)

PII redaction before sending, delimiter-wrapped untrusted data, output validated field by field and
never executed, per-user rate limit, timeouts + circuit breaker, off by default
([ADR-022](decisions/ADR-022-ai-suggestion-only.md), [ai-features.md](ai-features.md)).

### Webhook verification pipeline (§7.2)

```
per-IP bucket → size ≤ 64 KB → headers well-formed → partner active → |now−ts| ≤ 300 s
→ HMAC(current | previous-in-grace) constant-time → per-partner bucket → parse + validate
→ insert (unique (partner, event)) → 201 | 200 duplicate        every step → webhook_deliveries
```

### Hardening checklist (MASTER_PROMPT §6.3)

| Item | Status |
|---|---|
| CORS only for configured origins | ✅ |
| Security headers on API (`nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy`, `Cache-Control: no-store`) and SPA (CSP etc. in nginx) | ✅ (HSTS is sent automatically by Spring Security on HTTPS) |
| No stack traces / SQL in responses | ✅ tested |
| Secrets from env, fail fast | ✅ tested |
| SQL injection safe by construction (JPA params only) | ✅ |
| Log redaction | ✅ masking + redacting `toString()` |
| Request body limits (webhook 64 KB) | ✅ (filter, before buffering; nginx 1 MB overall) |
| Dependency scanning | ✅ Dependabot (Maven, npm ×2, Docker, Compose, Actions) + `npm audit --audit-level=high` in CI. OWASP dependency-check was not added: it needs an NVD API key and minutes of download per run, and Dependabot alerts cover the same advisories |
| Security headers verified on the real stack | ✅ Playwright `security-headers.spec.ts` (CSP, framing, nosniff, referrer, permissions, cache rules, no internals in errors) |
| Metrics not public | ✅ actuator on unpublished port 8081 in `prod`; CI asserts `/actuator/prometheus` isn't served on 8080 |
