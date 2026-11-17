# API reference

## Simple explanation

The backend is a JSON API under `/api/v1`. You sign in once, get an access token, and send it with
every request as `Authorization: Bearer <token>`. Every error has the same shape and a short
**code** (like `LAST_ADMIN`) that tells the app exactly what went wrong. From Phase 3, Swagger UI at
`/swagger-ui` lists every endpoint interactively.

## Technical details

### Conventions

- Base path `/api/v1`, JSON, plural nouns, actions as sub-resources.
- Auth: `Authorization: Bearer <JWT>` on everything except login/refresh/logout and health.
- Every response carries `X-Request-Id`. Send your own (8–64 chars `[A-Za-z0-9._-]`) to trace a call
  end to end.
- Success bodies are plain DTOs; lists are arrays or `PageResponse` (Phase 3). No envelope
  ([ADR-007](decisions/ADR-007-response-and-error-format.md)).

### Error format (RFC 9457)

```http
HTTP/1.1 409 Conflict
Content-Type: application/problem+json
X-Request-Id: 4f1c2a...

{
  "type": "urn:untangle:problem:last-admin",
  "title": "Cannot remove the last active admin",
  "status": 409,
  "detail": "At least one active admin must remain",
  "instance": "/api/v1/agents/8c1e.../active",
  "code": "LAST_ADMIN",
  "traceId": "4f1c2a..."
}
```

Validation errors add `"errors": [{"field": "password", "message": "size must be between 10 and 72"}]`.
Rejected values are never echoed.

### Error codes

| Code | HTTP | When |
|---|---|---|
| `VALIDATION_FAILED` | 400 | invalid fields, malformed JSON, bad enum value |
| `NOT_FOUND` / `METHOD_NOT_ALLOWED` / `UNSUPPORTED_MEDIA_TYPE` | 404 / 405 / 415 | routing |
| `RATE_LIMITED` | 429 | + `Retry-After` header and `retryAfterSeconds` |
| `AUTH_UNAUTHENTICATED` | 401 | no / invalid / forged token |
| `AUTH_TOKEN_EXPIRED` | 401 | access token expired → call `/auth/refresh` |
| `AUTH_INVALID_CREDENTIALS` | 401 | wrong email or password (same for both) |
| `AUTH_ACCOUNT_LOCKED` | 423 | 5 failed attempts; `lockedUntil` |
| `AUTH_ACCOUNT_DISABLED` | 401 | deactivated agent (login, refresh or existing token) |
| `AUTH_REFRESH_INVALID` | 401 | missing, expired, logged-out or concurrently-rotated refresh token |
| `AUTH_REFRESH_REUSED` | 401 | rotated token presented again → session revoked |
| `ACCESS_DENIED` | 403 | permission missing |
| `AGENT_NOT_FOUND` | 404 | |
| `EMAIL_TAKEN` | 409 | duplicate email (case-insensitive) |
| `LAST_ADMIN` | 409 | demoting the last active admin |
| `CANNOT_DEACTIVATE_SELF` | 409 | |
| `AGENT_HAS_OPEN_TICKETS` | 409 | deactivating an agent with OPEN/IN_PROGRESS tickets |
| `VERSION_CONFLICT` | 409 | stale `version` (Phase 3) |
| `TICKET_*`, `INVALID_STATUS_TRANSITION`, `RESOLUTION_NOTE_REQUIRED`, `ASSIGNEE_REQUIRED`, `AGENT_INACTIVE` | | Phase 3 |
| `WEBHOOK_REJECTED`, `PAYLOAD_TOO_LARGE` | 401 / 413 | Phase 4 |
| `AI_UNAVAILABLE` | 503 | Phase 6 |
| `INTERNAL_ERROR` | 500 | unexpected; quote `traceId` |

Source of truth: `ErrorCode.java`.

### Auth: `/api/v1/auth`

| Method & path | Auth | Body | Response |
|---|---|---|---|
| `POST /login` | public | `{email, password}` | `200 TokenResponse` + `Set-Cookie: untangle_refresh` |
| `POST /refresh` | refresh cookie | — | `200 TokenResponse` + rotated cookie |
| `POST /logout` | refresh cookie (optional) | — | `204`, cookie cleared, session revoked |
| `GET /me` | bearer | — | `200 CurrentAgent` |

```json
// TokenResponse
{
  "accessToken": "eyJ...",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "expiresAt": "2026-11-18T10:15:00Z",
  "agent": {
    "id": "…", "fullName": "Asha Verma", "email": "admin@untangle.local", "role": "ADMIN",
    "permissions": ["AGENT_MANAGE", "TICKET_VIEW", "…"]
  }
}
```

### Agents

| Method & path | Permission | Body | Response |
|---|---|---|---|
| `GET /api/v1/agents` | any signed-in | — | `[{id, fullName, role}]` active agents (dropdowns; no emails) |
| `GET /api/v1/admin/agents` | `AGENT_MANAGE` | — | `[AgentAdminView]` everyone, incl. email, active, locked, lastLoginAt |
| `POST /api/v1/agents` | `AGENT_MANAGE` | `{fullName, email, password (10–72), role}` | `201 AgentAdminView` |
| `PATCH /api/v1/agents/{id}` | `AGENT_MANAGE` | `{fullName?, role?}` | `200 AgentAdminView` |
| `PATCH /api/v1/agents/{id}/active` | `AGENT_MANAGE` | `{active}` | `200 AgentAdminView` (idempotent) |

The admin list lives at `/admin/agents`, next to `/admin/partners` (Phase 4), rather than as a
role-dependent shape of `GET /agents`. That keeps one response type per endpoint, which is cleaner
for generated client types.

### Ops

`GET /actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`, `/actuator/info`
are public. Prometheus metrics arrive in Phase 10.

### Try it

```bash
TOKEN=$(curl -s -X POST localhost:3000/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"admin@untangle.local","password":"Untangle@2026"}' | sed -E 's/.*"accessToken":"([^"]+)".*/\1/')
curl -s localhost:3000/api/v1/auth/me -H "Authorization: Bearer $TOKEN"
```
