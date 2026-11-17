# How it works — Phase 2: Security

## Simple explanation

Phase 2 puts a **front desk** in front of Untangle:

- You sign in with email and password. You get a **15-minute pass** (access token) and a
  **single-use renewal ticket** (refresh token) hidden in a cookie that page scripts can't read.
- Every request shows the pass. The server checks it's genuine, then looks up who you are **right
  now** in the database. If an admin deactivated you a second ago, you're turned away even though
  your pass hasn't expired.
- When the pass expires, the app trades the renewal ticket for a new pass and a new ticket. If an old
  ticket ever comes back, someone must have copied it, so the whole session is cancelled.
- Guessing passwords is slowed to a crawl: 5 wrong tries lock the account for 15 minutes, and each
  IP and each email can only try a few times per minute.
- What you can do depends on your role (Agent, Lead, Admin), checked on the server for every
  request.
- Admins manage the team, with safety rails: you can't remove the last admin, can't deactivate
  yourself, and can't deactivate someone who still owns open tickets.
- Every error looks the same and carries a `traceId`, which leads straight to the matching log
  lines.

## Technical details

### 1. One authenticated request, step by step

```
GET /api/v1/admin/agents   Authorization: Bearer eyJ…
 │
 ├─ RequestIdFilter (HIGHEST_PRECEDENCE)      X-Request-Id → MDC["requestId"] → response header
 ├─ Spring Security filter chain
 │   ├─ BearerTokenAuthenticationFilter
 │   │   └─ NimbusJwtDecoder: HS256 signature, exp (±30 s), iss=untangle
 │   │   └─ JwtAgentAuthenticationConverter
 │   │        AgentDirectory.findSnapshot(sub)  ← Caffeine "agentPrincipal" (30 s)
 │   │        inactive → DisabledException → 401 AUTH_ACCOUNT_DISABLED
 │   │        authorities = RolePermissions.of(currentRole) + ROLE_x
 │   └─ AuthorizationFilter: /api/v1/admin/** requires ROLE_ADMIN  (before body parsing)
 ├─ DispatcherServlet → AgentController.listForAdmin()
 │   └─ @PreAuthorize("hasAuthority('AGENT_MANAGE')")  (second layer)
 └─ any exception → GlobalExceptionHandler → application/problem+json {code, traceId, …}
```

401/403 raised inside the security chain are handed to the same `GlobalExceptionHandler` (via
`HandlerExceptionResolver`), so they look exactly like every other error.

### 2. Login (`AuthService.login`)

1. **Rate limit:** Bucket4j token buckets per IP (20/min) and per email (10/min) → `429` +
   `Retry-After`. This runs first, because it's cheap and stops spraying before any BCrypt work.
2. **Unknown email:** run BCrypt against a dummy hash anyway (equal timing), then return `401
   AUTH_INVALID_CREDENTIALS`.
3. **Locked:** `423 AUTH_ACCOUNT_LOCKED` + `lockedUntil`, without checking the password.
4. **Wrong password:** atomic `UPDATE ... failed_login_attempts + 1`, then
   `UPDATE ... SET locked_until WHERE attempts >= 5`, each in its own transaction, so the count
   survives the failed request and parallel guesses can't lose increments.
5. **Deactivated:** `401 AUTH_ACCOUNT_DISABLED`.
6. **Success:** reset the counters, insert a refresh token (new family), issue a JWT, and set the
   cookie `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth; Max-Age=7d`.

### 3. Refresh (`AuthService.refresh` + `RefreshTokenStore.rotate`)

| Situation | Response | Side effect |
|---|---|---|
| live token | 200 + new JWT + new cookie | successor inserted; old row `revoked_at`, `replaced_by_id` (conditional UPDATE) |
| token already **rotated** (reuse) | 401 `AUTH_REFRESH_REUSED` | **whole family revoked** |
| logged-out / expired / unknown | 401 `AUTH_REFRESH_INVALID` | — |
| two tabs refresh the same token at once | one 200, one 401 `AUTH_REFRESH_INVALID` | loser's successor rolled back; no family revocation |
| agent deactivated | 401 `AUTH_ACCOUNT_DISABLED` | family revoked |

### 4. Admin rules (`AgentService`)

- **Last admin:** `SELECT ... FOR UPDATE` on active admins, then check that someone else remains.
  The row lock stops two admins from demoting each other simultaneously.
- **No self-deactivation**, and **no deactivation with open tickets**. The ticket check goes through
  `TicketWorkloadQueries`, because ArchUnit forbids touching another feature's repository.
- After commit, `AgentAccessChangedEvent` → `AgentCacheEvictor` (cache) and `SessionRevoker`
  (refresh tokens). Events keep the dependency direction one-way (auth → agent).

### 5. Configuration and fail-fast

`SecurityProperties` (record) validates at startup: `JWT_SECRET` must be base64 and ≥ 32 bytes,
otherwise the app exits with a clear message. `make .env` generates the secret
(`openssl rand -base64 48`), and Compose refuses to start without it (`${JWT_SECRET:?…}`). Dev and
test profiles carry their own fixed keys, clearly labelled.

### 6. Verified at the end of Phase 2

| Check | Result |
|---|---|
| `./mvnw verify` | **177 tests** pass (72 from Phase 1 + 105 new) |
| `make up` + curl through nginx | demo admin logs in; `/me` shows permissions; refresh 200; wrong password 401 Problem Details; logout 204, then refresh 401 |
| JSON logs | Logstash-format JSON with `requestId`; a client-sent `X-Request-Id` flows nginx → backend → logs → response |

### Questions your lead might ask

- **"Why look up the agent on every request? Isn't the JWT enough?"** A JWT can't be un-issued.
  Looking up the agent (cached 30 s) lets deactivation and role changes apply immediately, at the
  cost of about one tiny query per agent per 30 s.
- **"Why is the refresh token not a JWT?"** It must be revocable and single-use. An opaque random
  value with a DB row (hash only) gives both; a JWT gives neither.
- **"Why SHA-256 for refresh tokens but BCrypt for passwords?"** Passwords are guessable, so they
  need a slow hash. A 256-bit random token can't be guessed, so a fast hash suffices and allows an
  indexed lookup.
- **"Why 423 for a locked account? Doesn't that reveal the account exists?"** Only after 5 wrong
  passwords for that exact email. The brief wants a distinct "locked" UI state; it's a documented
  trade-off.
- **"Why disable CSRF?"** The API authenticates with a header that browsers don't attach
  automatically. The one cookie is `SameSite=Strict` and only reaches `/api/v1/auth`.
