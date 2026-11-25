# API reference

## Simple explanation

The backend is a JSON API under `/api/v1`. You sign in once, get an access token, and send it with
every request as `Authorization: Bearer <token>`. Every error has the same shape and a short
**code** (like `LAST_ADMIN`) that tells the app exactly what went wrong. Swagger UI at `/swagger-ui` lists
every endpoint interactively.

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
| `VERSION_CONFLICT` | 409 | the `version` you sent is stale; body has `currentVersion` |
| `TICKET_NOT_FOUND` | 404 | |
| `TICKET_CLOSED` | 409 | any mutation of a CLOSED ticket except reopen |
| `INVALID_STATUS_TRANSITION` | 409 | move not in the state machine (incl. same status; reopen of a non-closed ticket) |
| `RESOLUTION_NOTE_REQUIRED` | 400 | resolving without a note |
| `ASSIGNEE_REQUIRED` | 409 | starting work on an unassigned ticket; unassigning a non-OPEN ticket |
| `AGENT_INACTIVE` | 409 | assigning to a deactivated agent |
| `WEBHOOK_REJECTED` | 401 | any webhook authentication failure (deliberately generic, ADR-018) |
| `PAYLOAD_TOO_LARGE` | 413 | webhook body > 64 KB |
| `PARTNER_NOT_FOUND` / `PARTNER_CODE_TAKEN` | 404 / 409 | partner admin |
| `AI_UNAVAILABLE` | 503 | Phase 6 |
| `SERVICE_BUSY` | 503 | overloaded: no database connection free within 5 s; `Retry-After: 2` |
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

### Tickets: `/api/v1/tickets`

| Method & path | Permission | Body | Notes |
|---|---|---|---|
| `GET /` | `TICKET_VIEW` | — | filters below; `PageResponse<TicketSummary>` |
| `POST /` | `TICKET_CREATE` | `{subject 5–150, description 10–5000, customerName 2–120, customerEmail?, category, priority, assigneeId?}` | `201` + `Location`; agents may set `assigneeId` only to themselves |
| `GET /{id}` | `TICKET_VIEW` | — | `TicketDetail` incl. `allowedActions`, `deniedActions[{action, reason}]`, `slaRemainingSeconds`, `version` |
| `GET /by-number/{UNT-000123}` | `TICKET_VIEW` | — | case-insensitive |
| `PATCH /{id}` | policy `EDIT` | `{subject?, description?, category?, priority?, version}` | priority change recomputes `slaDueAt` |
| `PUT /{id}/assignee` | policy `ASSIGN` / `SELF_ASSIGN` | `{agentId \| null, version}` | |
| `POST /{id}/transitions` | policy per action | `{targetStatus, note?, reason?, version}` | note for →RESOLVED; reason for OPEN→CLOSED, RESOLVED→IN_PROGRESS |
| `POST /{id}/reopen` | `TICKET_REOPEN` | `{reason, version}` | CLOSED → OPEN only |
| `POST /{id}/escalate` | policy `ESCALATE` | `{reason}` | OPEN/IN_PROGRESS only |
| `GET /{id}/comments` | `TICKET_VIEW` | — | timeline oldest first: `{id, type, body, author?, metadata?, createdAt}` |
| `POST /{id}/comments` | policy `COMMENT` | `{body 1–5000, type: COMMENT \| RESOLUTION_NOTE}` | `201`; `SYSTEM` can't be created via API |
| `POST /bulk/assign` | `TICKET_ASSIGN_ANY` | `{ticketIds[≤200], agentId}` | `{assigned[], skipped[{ticketId, reason}]}` |

**List filters** (all optional, combinable): `status` (repeatable), `priority` (repeatable),
`category` (repeatable), `assigneeId`, `unassigned=true`, `mine=true`, `escalated=true`,
`slaBreached=true`, `q` (case-insensitive contains over number, subject, customer, description;
`%` and `_` are literal), `createdFrom`, `createdTo` (ISO-8601).

**Paging and sorting:** `page` ≥ 0, `size` 1–100 (**values > 100 are rejected with 400, not
clamped**, so clients find out), `sort=field[,asc|desc]` with field ∈ `createdAt` (default desc),
`updatedAt`, `priority` (by severity), `status` (lifecycle order), `slaDueAt`, `ticketNumber`.
Unknown values → 400 with `errors[].field`.

**Allowed actions:** `EDIT, ASSIGN, SELF_ASSIGN, START_PROGRESS, RELEASE, RESOLVE,
RETURN_TO_PROGRESS, CLOSE, REOPEN, COMMENT, ESCALATE`
([ADR-015](decisions/ADR-015-allowed-actions-from-server.md)).

### Dashboard: `/api/v1/dashboard`

| Method & path | Permission | Response |
|---|---|---|
| `GET /summary` | `DASHBOARD_VIEW` | `{unresolved, byStatus{4}, unresolvedByPriority{4}, slaBreached, escalated, unassigned, myOpen, generatedAt}` |
| `GET /trends?days=14` | `DASHBOARD_VIEW` | `[{date, created, resolved}]` per UTC day, zeros included; `days` 1–90 |
| `GET /agents-workload` | `AGENT_WORKLOAD_VIEW` (leads/admins) | `[{agentId, fullName, role, open, inProgress, breached, total}]` |

Every summary number equals the `totalElements` of the matching list query (same
Specifications, tested).

### Partner webhook

`POST /api/v1/webhooks/partners/tickets`: HMAC-authenticated, no JWT. Full contract, signing
examples and retry advice: [webhook-integration-guide.md](webhook-integration-guide.md).
`201 {ticketId, ticketNumber, duplicate:false}` · `200 {..., duplicate:true}`.

### Admin: partners and webhook logs (`/api/v1/admin`, admins only)

| Method & path | Body | Response |
|---|---|---|
| `GET /partners` | — | `[PartnerView{id, code, name, active, rateLimitPerMinute, previousSecretValidUntil}]` (never secrets) |
| `POST /partners` | `{code, name, rateLimitPerMinute?}` | `201 {partner, secret, notice}`, **secret shown once** |
| `PATCH /partners/{id}` | `{name?, rateLimitPerMinute?}` | `PartnerView` |
| `POST /partners/{id}/rotate-secret` | — | `{partner, secret, notice}`; old secret valid 24 h |
| `PATCH /partners/{id}/active` | `{active}` | `PartnerView` |
| `GET /webhook-deliveries?partnerId&outcome&from&to&page&size≤100` | — | `PageResponse<DeliveryView{receivedAt, partnerCode, eventId, outcome, reason, ticketId, ticketNumber, sourceIp, payloadSha256}>` newest first |

### Notifications (`/api/v1/notifications`, the caller's own)

| Method & path | Response |
|---|---|
| `GET /?unreadOnly=false&page&size≤100` | `PageResponse<{id, type, ticketId, ticketNumber, message, read, createdAt}>` newest first |
| `GET /unread-count` | `{count}` (the SPA polls this every 15 s and pops up new ones, ADR-021) |
| `PATCH /{id}/read` | the notification (someone else's → 404) |
| `POST /read-all` | `{count: 0}` |

Types: `ASSIGNED` (to the new assignee), `ESCALATED` (manual: leads + assignee), `SLA_BREACH`
(scheduler: leads + assignee), `REOPENED` (assignee). Nobody is notified about their own action.

### AI (optional; 503 `AI_UNAVAILABLE` when off)

| Method & path | Permission | Body | Response |
|---|---|---|---|
| `GET /api/v1/ai/status` | signed in | — | `{enabled, model}` |
| `POST /api/v1/ai/triage` | `AI_USE` | `{subject, description}` | `{insightId, category?, priority?, summary, sentiment?, rationale?, model, promptVersion}` |
| `POST /api/v1/tickets/{id}/ai/summary` | `AI_USE` | — | `{insightId, summary, keyPoints[], nextStep?, ...}` |
| `POST /api/v1/tickets/{id}/ai/reply-draft` | `AI_USE` | — | `{insightId, draft, ...}` |
| `PATCH /api/v1/ai/insights/{id}/feedback` | `AI_USE` (own insight) | `{accepted}` | 204 |

### AI teammate ([ADR-028](decisions/ADR-028-ai-teammate-proposes-human-approves.md))

Assign a ticket to the AI with the normal `PUT /tickets/{id}/assignee` (leads); it works in the
background and proposes. Errors: `AI_PROPOSAL_NOT_FOUND` (404), `AI_PROPOSAL_STALE` (409),
`AI_TEAMMATE_FIXED` (409, changing the AI's role).

| Method & path | Who | Body | Response |
|---|---|---|---|
| `GET /api/v1/ai/teammate` | signed in | — | `{agentId, name, active, enabled}` |
| `GET /api/v1/tickets/{id}/ai-proposal` | can view the ticket | — | `{proposal: {id, reply, resolutionNote, confidence, status, model, promptVersion, createdAt, decidedBy?, decidedAt?, canDecide} \| null}` |
| `POST /api/v1/tickets/{id}/ai-proposal/approve` | lead/admin | `{version}` | proposal (`APPROVED`); ticket RESOLVED with its note |
| `POST /api/v1/tickets/{id}/ai-proposal/take-over` | lead/admin | `{version}` | proposal (`REJECTED`); ticket assigned to the caller |

Details and safety: [ai-features.md](ai-features.md).

### Auto-assign ([ADR-029](decisions/ADR-029-automatic-assignment-least-loaded.md))

Leads and admins only (`TICKET_ASSIGN_ANY`). Each waiting ticket goes to the least busy eligible
agent (weighted load CRITICAL 4 · HIGH 3 · MEDIUM 2 · LOW 1), most urgent SLA first. New tickets
are assigned right after they are created, so a `POST /tickets` response may still show no
assignee; the detail fetched afterwards shows it.

| Method & path | Body | Response |
|---|---|---|
| `GET /api/v1/auto-assign` | — | `{enabled, maxOpenPerAgent, waiting, agents: [{agentId, fullName, role, ai, autoAssign, skills[], openTickets, load, lastAutoAssignedAt?}]}` |
| `PUT /api/v1/auto-assign/settings` | `{enabled, maxOpenPerAgent: 1..100}` | the overview |
| `PUT /api/v1/auto-assign/agents/{agentId}` | `{autoAssign, skills: Category[]}` (empty = all) | the overview; 404 `AGENT_NOT_FOUND` for an unknown or inactive agent |
| `POST /api/v1/auto-assign/run` | — | `{assigned, waiting}`; works even while the switch is off |

### Agent performance ([ADR-030](decisions/ADR-030-agent-performance-from-resolution-facts.md))

Leads and admins only (`AGENT_WORKLOAD_VIEW`; agents get 403). Durations are whole seconds;
metrics are `null` when nothing was resolved.

| Method & path | Response |
|---|---|
| `GET /api/v1/dashboard/agent-performance?days=30` (1..365) | `{days, from, to, minResolvedToRank, team: Metrics, agents: [{agentId, fullName, role, ai, active, openNow, ranked, metrics: Metrics}]}` with `Metrics = {resolved, avgHandleSeconds, medianHandleSeconds, medianResolutionSeconds, slaMetPercent, reopenPercent}` |

### Swagger / OpenAPI

Interactive docs: <http://localhost:8080/swagger-ui> · machine-readable spec:
<http://localhost:8080/v3/api-docs>. Click *Authorize* and paste an access token.

### Ops

`GET /actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`, `/actuator/info`
are public. Prometheus metrics arrive in Phase 10.

### Try it

```bash
TOKEN=$(curl -s -X POST localhost:3000/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"admin@untangle.local","password":"Untangle@2026"}' | sed -E 's/.*"accessToken":"([^"]+)".*/\1/')
curl -s localhost:3000/api/v1/auth/me -H "Authorization: Bearer $TOKEN"
```
