# MASTER PROMPT — Untangle: Customer Support Ticket & Escalation System

> **Product name:** Untangle. **Tagline:** "Every complaint, untangled."
> **Logo / 3D idea:** a knotted line that straightens into a checkmark (also the login-page hero animation).
> **Ticket IDs:** `UNT-000123`.
>
> **Decisions agreed in Phase 0 planning** (they override the original text below wherever the two differ; each one has an ADR or an entry in `docs/assumptions-and-open-questions.md`):
> - Java package root is `dev.untangle` (personal project, no employer names anywhere).
> - Java **21 LTS** + Spring Boot **4.1.x**. PostgreSQL **18** (the original text said 16).
> - Local toolchain for dev (`make test`, `make lint`), Docker for runtime (`make up`) and CI.
> - Docker Postgres is published on host port **5433** (the developer's Homebrew Postgres uses 5432).
> - TypeScript is pinned to **6.0.x** (typescript-eslint does not support 7.x yet).
> - Tailwind v4 has no JS config: tokens are mapped with `@theme` in CSS.
> - Success responses are plain DTOs (lists use `PageResponse`); errors are RFC 9457 only. No `ApiResponse` envelope.
> - Resolved ambiguities R1–R14 are listed in `docs/assumptions-and-open-questions.md`.
> - Phase 1: primary keys are **UUIDv7** (PG 18 `uuidv7()`; ADR-008) instead of `gen_random_uuid()`.
>   Demo data lives in `db/seed-demo` under a `demo` profile (included by `dev`). Hash columns
>   are `varchar(64)` + regex CHECK. Extra DB invariants beyond §8 are listed in `docs/database.md`.
>   Feature packages use `api/application/domain/infrastructure` uniformly.
> - Phase 2: JWT via Spring Security OAuth2 Resource Server (HS256; ADR-013), not jjwt. Native Boot
>   structured logging instead of logstash-logback-encoder. Locked accounts → 423. Admin agent list at
>   `/api/v1/admin/agents`. Extra error codes listed in docs/api.md. `TicketAccessPolicy` moves to
>   Phase 3; MapStruct starts in Phase 3.
> - Phase 3: `deniedActions` returned next to `allowedActions` (ADR-015); V2 adds `sla_started_at`
>   and severity/status rank columns; page size > 100 is rejected (400); `GET /tickets/by-number/{n}`
>   added; reopen resets escalation level.
> - Phase 4: per-IP webhook limit before header checks (R2); simulator creates its own partners via the
>   admin API (13 scenarios, also in CI); demo partner `acme-bank` gets its secret at startup.

---

## 0. Your role and how we work together

You are a **senior full-stack engineer and tech lead** building a production-grade project with me (an SDE intern at a fintech company). Treat this like a real company codebase that will be reviewed by senior engineers.

### Working agreement (follow strictly)
1. **Work in phases** (Section 14). At the start of each phase, write a short plan (files you will create/change, decisions involved). After each phase: run all tests, lint and type checks, summarise what was done, list any deviations, and **STOP and wait for my "continue"**.
2. **Never silently change scope or a decision.** If something in this document is wrong, risky or impossible, say so and propose an alternative. Every non-trivial decision gets an ADR in `docs/decisions/`.
3. **Do not invent library APIs.** For React Bits, React Three Fiber/drei, Spring Boot, Groq and any library whose API you are unsure of, check the official docs or installed source before using it. Pin versions.
4. **Tests are part of "done".** A feature without tests is not done. Business rules and edge cases (Section 11) must each have at least one test.
5. **Explain as you go.** I must be able to explain every line to my lead. After each phase, add a "How it works" section for that phase to `docs/` in simple language plus technical language.
6. **Keep docs in sync with the code** (Section 13). If code changes a decision, update the doc in the same commit.
7. Use **Conventional Commits** (`feat:`, `fix:`, `test:`, `docs:`, `chore:`, `refactor:`), one logical change per commit.
8. In Phase 0, create a concise `CLAUDE.md` at the repo root that summarises conventions, commands and architecture from this file so future sessions keep the context.

---

## 1. The problem we are solving

Customer support teams receive complaints from customers directly and from **partner systems** (other companies' software that forwards complaints automatically). Without a system, complaints get lost, nobody owns them, urgent issues wait behind trivial ones, managers cannot see the backlog, and retried partner messages create duplicate work.

We are building a **support ticket platform** where every complaint becomes a ticket that is **prioritised, assigned, tracked through a lifecycle, commented on, escalated if it breaches its SLA, and never duplicated**. Partner systems submit tickets through a **signed webhook** verified with **HMAC**.

### Original requirements (must all be met, end to end)
1. Create tickets with customer name, issue description, category and priority.
2. Assign tickets to support agents.
3. Ticket statuses: `OPEN`, `IN_PROGRESS`, `RESOLVED`, `CLOSED`.
4. Add comments or resolution notes to tickets.
5. Filter by priority, assigned agent and status.
6. Show the number of unresolved tickets on a dashboard.
7. **HMAC webhook:** a simulated external partner creates tickets via signed webhook requests; the backend validates the HMAC before accepting anything.
8. **Business rules:** closed tickets cannot be modified without reopening; every ticket must have a valid priority; duplicate external event IDs must never create duplicate tickets.
9. Stack: **React** frontend, **Spring Boot** backend, **PostgreSQL** (tables at least `tickets`, `agents`, `ticket_comments`).
10. Screens: ticket dashboard, ticket detail page, create/assign ticket form.

### Product principle
**Every feature must have a clear purpose for a support agent, a team lead or an admin.** No decorative features with no user value. For each feature, the docs must state *who uses it and why*.

---

## 2. Assumptions (open questions to the lead, with our defaults)

These were not specified. Implement the defaults below, keep them **configurable or isolated** so they are easy to change, and list them in `docs/assumptions-and-open-questions.md`.

| # | Question | Default we implement |
|---|---|---|
| A1 | Allowed status transitions | See state machine, Section 5 |
| A2 | Where does a reopened ticket go? | `CLOSED → OPEN`; keeps the previous assignee; `reopened_count++`; reason required |
| A3 | Comments on CLOSED tickets? | **Not allowed** (closed = fully immutable until reopened) |
| A4 | Is a resolution note required to resolve? | **Yes**, `IN_PROGRESS → RESOLVED` requires a non-empty resolution note |
| A5 | What does "unresolved" mean? | `OPEN` + `IN_PROGRESS` |
| A6 | Priority values | `LOW`, `MEDIUM`, `HIGH`, `CRITICAL` |
| A7 | Category values | `BILLING`, `TECHNICAL`, `ACCOUNT`, `REFUND`, `GENERAL`, `OTHER` |
| A8 | Duplicate webhook response | `200 OK` with the existing ticket id and `"duplicate": true` (partners retry on non-2xx) |
| A9 | Event ID uniqueness scope | Per partner: `UNIQUE(partner_id, external_event_id)` |
| A10 | Auth / roles | JWT auth with roles `ADMIN`, `LEAD`, `AGENT` (Section 6) |
| A11 | Escalation meaning | SLA-based automatic escalation plus manual escalation (Section 5.4) |
| A12 | Agent onboarding | No public sign-up; an ADMIN creates agents; first admin is seeded from env vars |
| A13 | Can agents be deleted? | Never hard-deleted; deactivated only (preserves history) |

---

## 3. Tech stack (use latest stable versions; pin them)

### Backend
- **Java 21 (LTS)**, **Spring Boot** latest stable (check the current version), Maven.
- Spring Web, Spring Security, Spring Data JPA (Hibernate), Bean Validation.
- **PostgreSQL 18**, **Flyway** migrations (`ddl-auto=validate`, never `update`).
- JWT: `jjwt` or Spring Security OAuth2 Resource Server with symmetric/asymmetric keys (choose one, write an ADR).
- **MapStruct** for DTO mapping; Lombok only for boilerplate on entities/DTOs (no `@Data` on JPA entities).
- **springdoc-openapi** (Swagger UI at `/swagger-ui`, spec at `/v3/api-docs`).
- **Resilience4j** (circuit breaker + timeout for the Groq AI calls).
- **Bucket4j** for rate limiting (login, webhook, AI endpoints).
- **ShedLock** for the scheduled SLA job (safe when running multiple instances).
- **Redis** (optional profile, bonus): dashboard cache and distributed rate limiting.
- Spring Boot **Actuator** + **Micrometer Prometheus** registry.
- Structured JSON logging (Logback + logstash encoder) with a correlation ID.
- Tests: **JUnit 5, Mockito, AssertJ, Spring Boot Test, MockMvc, Testcontainers (PostgreSQL), ArchUnit, JaCoCo**.

### Frontend
- **React 19 + TypeScript (strict) + Vite**.
- **Tailwind CSS** with design tokens (Section 9).
- **React Bits** (reactbits.dev) animated components, installed via their official CLI (shadcn or jsrepo). Check the docs for the exact command and component names; use the TypeScript + Tailwind variants.
- **React Three Fiber + @react-three/drei** for 3D; **Motion (Framer Motion)** for transitions and layout animations.
- **TanStack Query** (server state), **Zustand** (small client state: auth session, UI prefs).
- **React Router** (data routers, lazy routes).
- **React Hook Form + Zod** (schemas mirror backend validation).
- **Axios** with interceptors (auth header, single-flight token refresh, correlation id).
- **openapi-typescript** to generate API types from the backend OpenAPI spec (no hand-written API types drifting from the backend).
- Recharts (or another library chosen in an ADR) for charts; `sonner` for toasts; `cmdk` for a command palette.
- Tests: **Vitest + React Testing Library + MSW**, **Playwright** for E2E.
- ESLint + Prettier, `tsc --noEmit` in CI.

### Infra and tooling
- **Docker + Docker Compose** (I have Docker installed).
- **GitHub Actions** CI.
- `Makefile` (or `justfile`) with commands: `make up`, `make down`, `make test`, `make simulate`, `make seed`, `make logs`.
- `.env.example` with every variable documented; **no secrets committed**.

---

## 4. Architecture (HLD)

### 4.1 Components
```
                 ┌──────────────────────────────┐
 Partner         │  Spring Boot (modular monolith)
 Simulator ──────┼─▶ HMAC Filter ─▶ Webhook ──┐  │
 (signed POST)   │                             ▼  │
                 │                      Ticket Service ──▶ PostgreSQL
 React SPA ──────┼─▶ JWT Filter ─▶ REST ──────▲  │          ▲
 (agents/leads)  │        │                   │  │          │
                 │        └──▶ RBAC checks    │  │      Redis (optional)
                 │  SLA Scheduler (ShedLock) ─┘  │
                 │  AI Service ──▶ Groq API (circuit breaker)
                 └──────────────────────────────┘
```
- **Two entry doors, one brain:** agent requests (JWT) and partner webhooks (HMAC) both go through the same `TicketService`, so business rules are identical for both.
- **Stateless backend** (JWT, no server session), so it can scale horizontally.
- **Modular monolith, package-by-feature:** each feature owns its controller, service, repository, domain, DTOs and mapper. ArchUnit tests enforce the boundaries.

### 4.2 Backend folder structure
```
backend/
├── src/main/java/dev/untangle/
│   ├── UntangleApplication.java
│   ├── common/
│   │   ├── api/            # PageResponse wrapper, pagination utils
│   │   ├── error/          # GlobalExceptionHandler, ErrorCode enum, domain exceptions
│   │   ├── audit/          # BaseEntity (createdAt, updatedAt, version)
│   │   ├── logging/        # CorrelationIdFilter, MDC helpers
│   │   └── util/
│   ├── config/             # Jackson, OpenAPI, CORS, Clock, Async, Scheduling, properties classes
│   ├── security/
│   │   ├── jwt/            # JwtService, JwtAuthenticationFilter
│   │   ├── rbac/           # Role, Permission, @PreAuthorize helpers, TicketAccessPolicy
│   │   ├── crypto/         # AES-GCM SecretCipher (for partner secrets)
│   │   └── SecurityConfig.java
│   ├── auth/               # login, refresh, logout, me — controller/service/dto/repository
│   ├── agent/              # agents CRUD (admin), workload — controller/service/repository/domain/dto/mapper
│   ├── ticket/
│   │   ├── api/            # TicketController, DTOs, request validation
│   │   ├── application/    # TicketService, TicketQueryService (filters via Specifications)
│   │   ├── domain/         # Ticket entity, TicketStatus, Priority, Category, TicketStateMachine
│   │   ├── infrastructure/ # TicketRepository, TicketSpecifications, TicketNumberGenerator
│   │   └── mapper/
│   ├── comment/            # comments + system timeline events
│   ├── webhook/
│   │   ├── hmac/           # HmacVerifier, CachedBodyFilter, WebhookSignatureFilter
│   │   ├── partner/        # Partner entity, admin APIs, secret rotation
│   │   └── delivery/       # WebhookDelivery log (audit of every webhook attempt)
│   ├── escalation/         # SLA policy, SlaBreachScheduler, manual escalate
│   ├── notification/       # in-app notifications (+ optional SSE stream)
│   ├── dashboard/          # summary + trends queries (cached)
│   └── ai/                 # GroqClient, AiTriageService, AiSummaryService, prompt templates, PII redactor
├── src/main/resources/
│   ├── application.yml, application-dev.yml, application-prod.yml, application-test.yml
│   ├── db/migration/       # V1__init.sql, V2__..., (dev seed lives in db/seed-dev, dev profile only)
│   └── prompts/            # AI prompt templates (versioned text files)
└── src/test/java/...       # mirrors main; unit/, integration/, architecture/
```
Layering rule: `controller → service → repository`. Controllers never touch repositories or entities directly; they only use DTOs. Entities never leave the service layer.

### 4.3 Frontend folder structure
```
frontend/
├── src/
│   ├── app/            # App.tsx, router.tsx, providers (QueryClient, Theme, Auth), error boundary
│   ├── features/
│   │   ├── auth/       # login page, session store, guards (RequireAuth, RequireRole)
│   │   ├── dashboard/  # KPI cards, charts, unresolved counter
│   │   ├── tickets/    # list/table, filters, detail, create/assign form, timeline, kanban
│   │   ├── admin/      # agents, partners & secret rotation, webhook delivery logs
│   │   ├── ai/         # triage suggestions, summary, reply draft panels
│   │   └── notifications/
│   ├── components/
│   │   ├── ui/         # design-system primitives (Button, Card, Badge, Input, Dialog…)
│   │   ├── reactbits/  # React Bits components (installed via CLI, adapted to tokens)
│   │   ├── three/      # R3F scenes (lazy-loaded), WebGL fallback
│   │   └── layout/     # AppShell, Sidebar/Dock, Topbar, CommandPalette
│   ├── lib/            # api client, query keys, generated OpenAPI types, utils, zod schemas
│   ├── hooks/
│   ├── styles/         # tokens.css, globals.css
│   └── test/           # MSW handlers, test utils
├── e2e/                # Playwright specs
└── vite.config.ts
```

### 4.4 Repo root
```
/
├── backend/  frontend/  partner-simulator/
├── docs/                      # Section 13
├── infra/                     # prometheus.yml, grafana dashboards, nginx.conf, k6 scripts
├── docker-compose.yml         # profiles: default, simulator, observability
├── .github/workflows/ci.yml
├── Makefile  .env.example  CLAUDE.md  README.md
```

---

## 5. Domain and business rules

### 5.1 Ticket state machine (single source of truth: `TicketStateMachine`)
| From | To | Who | Conditions |
|---|---|---|---|
| OPEN | IN_PROGRESS | assignee, LEAD, ADMIN | ticket must be assigned; sets `first_response_at` if null |
| OPEN | CLOSED | LEAD, ADMIN | `reason` required (spam / duplicate / invalid) |
| IN_PROGRESS | OPEN | assignee, LEAD, ADMIN | "release back to queue"; clears assignee |
| IN_PROGRESS | RESOLVED | assignee, LEAD, ADMIN | **resolution note required**; sets `resolved_at` |
| RESOLVED | IN_PROGRESS | assignee, LEAD, ADMIN | fix didn't work; reason required |
| RESOLVED | CLOSED | LEAD, ADMIN (and assignee, configurable) | sets `closed_at` |
| CLOSED | OPEN | LEAD, ADMIN, via `/reopen` only | reason required; `reopened_count++` |

- Any other transition → `409 INVALID_STATUS_TRANSITION`.
- Same-status "transition" → `409 INVALID_STATUS_TRANSITION` (no silent no-op).
- Every transition writes a `SYSTEM` comment (`from`, `to`, `actor`, `reason`) to the timeline.
- `GET /tickets/{id}` returns **`allowedActions`**, computed server-side from the state machine **and** RBAC, so the UI never duplicates the rules; it only shows what the server allows.

### 5.2 Closed-ticket immutability
When `status = CLOSED`, **every** mutation (edit fields, assign, comment, escalate, transition other than reopen) returns `409 TICKET_CLOSED`. Enforce this in the service layer with one guard method used by all mutations, and test it for every mutation endpoint.

### 5.3 Validation
- `priority`: required, must be a valid enum value (case-sensitive; reject `"high"`, `"URGENT"`, null, empty) → `400 VALIDATION_FAILED` with field errors.
- `customerName`: required, trimmed, 2–120 chars. `customerEmail`: optional, valid email.
- `subject`: required for manual tickets (5–150 chars); for webhook tickets, if missing, derive it from the first 80 chars of the description.
- `description`: required, 10–5000 chars. `category`: required enum.
- Comment body: 1–5000 chars. Strip/escape HTML (render as plain text on the frontend; never `dangerouslySetInnerHTML`).
- All timestamps stored as `timestamptz` in UTC; API uses ISO-8601.

### 5.4 SLA and escalation (the "Escalation" in the title, with a purpose)
- SLA resolution targets by priority (configurable in `application.yml`): `CRITICAL 1h`, `HIGH 4h`, `MEDIUM 24h`, `LOW 72h`. `sla_due_at` is set at creation and recalculated if the priority changes.
- `SlaBreachScheduler` (every minute, ShedLock-protected) finds unresolved tickets past `sla_due_at` that are not yet escalated for that level → increments `escalation_level`, writes a SYSTEM comment, notifies the assignee and all LEADs.
- Manual escalation: `POST /tickets/{id}/escalate` (any agent with access, reason required) → `escalation_level++`, notifies LEADs.
- Purpose: leads see breached/at-risk tickets immediately; nothing silently rots.

### 5.5 Optimistic locking
Every mutable entity has `@Version`. Mutating requests on a ticket send the `version` they last saw; a mismatch → `409 VERSION_CONFLICT`. The UI shows "This ticket was updated by someone else — refresh" with a diff-friendly reload.

---

## 6. Authentication, authorisation and RBAC

### 6.1 Auth flow
- `POST /api/v1/auth/login` → returns a short-lived **access token (JWT, 15 min)** in the response body; sets a **refresh token (7 days)** in an `HttpOnly; Secure; SameSite=Strict` cookie scoped to `/api/v1/auth`.
- Frontend keeps the access token **in memory only** (not localStorage). On 401, Axios does a **single-flight** refresh, then retries the original request.
- **Refresh token rotation with reuse detection:** refresh tokens are stored **hashed** (SHA-256) with a `family_id`. Using an already-rotated token revokes the whole family (likely theft) and forces re-login.
- Passwords: **BCrypt (strength 12)**, or Argon2 with an ADR. Password policy: min 10 chars.
- Brute-force protection: rate limit login per IP + email; lock the account for 15 min after 5 failed attempts.
- Deactivated agents: login rejected; refresh rejected; existing access tokens are rejected because the JWT filter checks `active` via a short-TTL cached lookup.
- `POST /auth/logout` revokes the refresh token family and clears the cookie.

### 6.2 Roles and permissions
| Capability | AGENT | LEAD | ADMIN |
|---|---|---|---|
| View tickets / dashboard | ✅ | ✅ | ✅ |
| Create ticket (manual) | ✅ | ✅ | ✅ |
| Self-assign an unassigned ticket | ✅ | ✅ | ✅ |
| Assign/reassign to anyone | ❌ | ✅ | ✅ |
| Transition tickets assigned to self | ✅ | ✅ | ✅ |
| Transition any ticket | ❌ | ✅ | ✅ |
| Close / reopen | ❌ (configurable for RESOLVED→CLOSED on own) | ✅ | ✅ |
| Comment (non-closed tickets) | ✅ | ✅ | ✅ |
| Edit ticket fields (priority etc.) | own tickets | ✅ | ✅ |
| Escalate | ✅ | ✅ | ✅ |
| Manage agents (create, role, deactivate) | ❌ | ❌ | ✅ |
| Manage partners, rotate secrets, view webhook logs | ❌ | ❌ | ✅ |

- Implement as **permissions** (e.g. `TICKET_ASSIGN_ANY`) mapped to roles, checked with `@PreAuthorize` for coarse rules and a `TicketAccessPolicy` for ownership rules ("is this the assignee?").
- Admin safety rules: cannot deactivate or demote **the last active ADMIN**; cannot deactivate yourself.
- Deactivating an agent who still has unresolved tickets → `409 AGENT_HAS_OPEN_TICKETS` (the UI offers bulk reassign first).

### 6.3 Security hardening checklist
- CORS: allow only the frontend origin from config.
- Security headers: CSP (frontend nginx), `X-Content-Type-Options`, `Referrer-Policy`, `Frame-Options DENY`, HSTS in prod.
- Request body size limits (webhook max 64 KB).
- No stack traces or SQL errors in API responses.
- Secrets only from env vars; fail fast at startup if a required secret is missing or too short.
- SQL injection safe by construction (JPA params/Specifications only, no string-concatenated queries).
- Log redaction: never log passwords, tokens, signatures, partner secrets or full customer emails.
- Dependency scanning in CI (OWASP dependency-check or `npm audit` + Dependabot).

---

## 7. HMAC webhook (core requirement — implement carefully)

### 7.1 Contract (document fully in `docs/webhook-integration-guide.md`)
`POST /api/v1/webhooks/partners/tickets`

Headers:
```
Content-Type: application/json
X-Partner-Id: <partner code, e.g. "acme-bank">
X-Event-Id: <unique id per event from the partner, e.g. "evt_01H...">
X-Timestamp: <unix epoch seconds>
X-Signature: sha256=<lowercase hex of HMAC-SHA256(secret, timestamp + "." + rawBody)>
```
Body:
```json
{
  "customerName": "Rahul Sharma",
  "customerEmail": "rahul@example.com",
  "subject": "Refund not received",
  "description": "I cancelled my order 7 days ago and the refund has not arrived.",
  "category": "REFUND",
  "priority": "HIGH"
}
```

### 7.2 Processing order (order matters)
1. **Size check** (≤ 64 KB) → else `413`.
2. **Required headers present** and well-formed → else `401` (generic body).
3. **Partner lookup** by `X-Partner-Id`, must be active → else `401`.
4. **Timestamp freshness:** `|now - timestamp| ≤ 300s` (configurable; reject the future too) → else `401`. Protects against replay.
5. **Signature verification on the raw request bytes** (cache the body in a filter *before* any JSON parsing; never re-serialise). Compare with `MessageDigest.isEqual` (constant time). Accept the **current secret or the previous secret during its rotation grace window** → else `401`.
6. **Rate limit per partner** → else `429` with `Retry-After`.
7. **Parse + validate** the payload (same rules as manual tickets) → else `400` with field errors. A valid signature does not mean valid data.
8. **Idempotent create:** insert the ticket with `source=WEBHOOK, partner_id, external_event_id`. Rely on the **DB unique constraint** `(partner_id, external_event_id)`; on `DataIntegrityViolationException`, load and return the existing ticket. Never use check-then-insert alone (race condition).
   - New → `201 {ticketId, ticketNumber, duplicate:false}`
   - Duplicate → `200 {ticketId, ticketNumber, duplicate:true}`
9. **Record every attempt** in `webhook_deliveries` (outcome + reason), including rejections, for the admin "Webhook logs" screen.

Response bodies for 401 are **generic** ("invalid webhook request") so attackers learn nothing; the precise reason is stored in `webhook_deliveries` for admins. Write an ADR on this tradeoff.

### 7.3 Partner secrets
- Generated server-side (32 random bytes, base64url), **shown once** at creation/rotation.
- The HMAC secret must be recoverable to compute signatures, so it **cannot be hashed**. Store it **encrypted with AES-256-GCM** using a master key from the `APP_SECRET_ENCRYPTION_KEY` env var. Document this in an ADR.
- Rotation: `POST /admin/partners/{id}/rotate-secret` → new secret becomes current; the old one stays valid for a grace window (default 24h).

### 7.4 Partner simulator (`partner-simulator/`)
A small standalone **Node.js + TypeScript CLI** (or Java — pick one in an ADR) that plays the role of an external partner company. It is **not** part of the backend and signs requests itself with the shared secret.

Scenarios (`npm run simulate -- --scenario <name>` or `--scenario all`), each printing expected vs actual status in a results table:
- `valid` → 201
- `duplicate` (same event id twice) → 201 then 200 with the same ticket id
- `concurrent-duplicate` (20 parallel requests, same event id) → exactly one ticket created
- `tampered-body` (sign, then modify body) → 401
- `wrong-secret` → 401
- `expired-timestamp` (10 min old) → 401
- `future-timestamp` → 401
- `missing-signature` → 401
- `unknown-partner` → 401
- `invalid-priority` (correctly signed) → 400
- `oversized-payload` → 413
- `burst` (rate limit) → some 429s
- `rotation` (old secret within grace window) → 201

Also runnable as a Docker Compose service under the `simulator` profile. Include signing snippets for curl/bash, Node, Java and Python in the integration guide.

---

## 8. Database design

Use **UUID primary keys** (`gen_random_uuid()`), `timestamptz`, `VARCHAR + CHECK` for enums (ADR: why not Postgres ENUM), and `version BIGINT` for optimistic locking. All schema changes go through Flyway.

### Tables
**agents** (support staff = system users)
`id, full_name, email (unique, case-insensitive via lower() unique index), password_hash, role CHECK(ADMIN|LEAD|AGENT), active, failed_login_attempts, locked_until, last_login_at, created_at, updated_at, version`

**tickets**
`id, ticket_number (unique, human-readable "UNT-000123" from a sequence), subject, description, customer_name, customer_email NULL, category CHECK, priority CHECK, status CHECK, assigned_agent_id FK→agents NULL ON DELETE RESTRICT, created_by_agent_id FK→agents NULL (NULL for webhook), source CHECK(MANUAL|WEBHOOK), partner_id FK→partners NULL, external_event_id NULL, escalation_level SMALLINT DEFAULT 0, sla_due_at, first_response_at, resolved_at, closed_at, reopened_count INT DEFAULT 0, created_at, updated_at, version`

Constraints:
- `UNIQUE (partner_id, external_event_id)` (partial index `WHERE external_event_id IS NOT NULL`)
- `CHECK ((source = 'WEBHOOK') = (external_event_id IS NOT NULL AND partner_id IS NOT NULL))`
- `CHECK (status <> 'CLOSED' OR closed_at IS NOT NULL)`

Indexes (justify each in `docs/database.md`):
`(status, priority)`, `(assigned_agent_id, status)`, `(created_at DESC)`, partial `(sla_due_at) WHERE status IN ('OPEN','IN_PROGRESS')`, `pg_trgm` GIN on `subject`/`description` for search (bonus).

**ticket_comments** (append-only timeline: comments, resolution notes, system events)
`id, ticket_id FK→tickets ON DELETE CASCADE, author_agent_id FK→agents NULL (NULL = system), type CHECK(COMMENT|RESOLUTION_NOTE|SYSTEM), body, metadata JSONB NULL (e.g. {"from":"OPEN","to":"IN_PROGRESS"}), created_at`
Index: `(ticket_id, created_at)`. Comments are immutable (audit trail) — ADR.

Supporting tables (beyond the suggested three; justify each in an ADR):
- **partners**: `id, code (unique), name, secret_ciphertext, previous_secret_ciphertext NULL, previous_secret_expires_at NULL, active, rate_limit_per_minute, created_at, updated_at, version`
- **webhook_deliveries**: `id, partner_id NULL, partner_code_raw, event_id, received_at, outcome CHECK(CREATED|DUPLICATE|REJECTED_SIGNATURE|REJECTED_TIMESTAMP|REJECTED_UNKNOWN_PARTNER|REJECTED_VALIDATION|REJECTED_TOO_LARGE|RATE_LIMITED), ticket_id NULL, reason, source_ip, payload_sha256` (do not store the raw payload: PII minimisation; 30-day retention job)
- **refresh_tokens**: `id, agent_id, token_hash (unique), family_id, expires_at, revoked_at NULL, replaced_by_id NULL, created_at, user_agent, ip`
- **notifications**: `id, recipient_agent_id, type CHECK(ASSIGNED|ESCALATED|SLA_BREACH|MENTION|REOPENED), ticket_id, message, read_at NULL, created_at`
- **ticket_ai_insights**: `id, ticket_id, kind CHECK(TRIAGE|SUMMARY|REPLY_DRAFT), content JSONB, model, prompt_version, accepted BOOLEAN NULL, created_by_agent_id, created_at`
- **shedlock** (ShedLock's own table)

Relationships (draw a Mermaid ER diagram in `docs/database.md`):
- agents 1 — N tickets (assigned), agents 1 — N tickets (created)
- tickets 1 — N ticket_comments; agents 1 — N ticket_comments
- partners 1 — N tickets; partners 1 — N webhook_deliveries
- agents 1 — N refresh_tokens; agents 1 — N notifications
- tickets 1 — N ticket_ai_insights

Dev seed (`dev` profile only): 1 admin, 2 leads, 6 agents, 1 partner, ~60 realistic tickets across all statuses/priorities/categories with comments, so the dashboard looks real in demos.

---

## 9. API design

- Base path `/api/v1`; JSON; plural nouns; actions as sub-resources.
- Pagination: `?page=0&size=20&sort=createdAt,desc` (max size 100) → `{content, page, size, totalElements, totalPages}`.
- Errors: **RFC 9457 Problem Details** (`application/problem+json`) with extra fields `code` (from the `ErrorCode` enum), `traceId`, `errors[]` (field validation).
- Error codes: `VALIDATION_FAILED, TICKET_NOT_FOUND, AGENT_NOT_FOUND, TICKET_CLOSED, INVALID_STATUS_TRANSITION, RESOLUTION_NOTE_REQUIRED, ASSIGNEE_REQUIRED, AGENT_INACTIVE, VERSION_CONFLICT, ACCESS_DENIED, AUTH_INVALID_CREDENTIALS, AUTH_ACCOUNT_LOCKED, AUTH_TOKEN_EXPIRED, AUTH_REFRESH_REUSED, LAST_ADMIN, AGENT_HAS_OPEN_TICKETS, WEBHOOK_REJECTED, RATE_LIMITED, AI_UNAVAILABLE, PAYLOAD_TOO_LARGE`.

### Endpoints
**Auth:** `POST /auth/login`, `POST /auth/refresh`, `POST /auth/logout`, `GET /auth/me`

**Tickets:**
- `POST /tickets` — create (manual)
- `GET /tickets` — filters: `status` (multi), `priority` (multi), `assigneeId`, `unassigned=true`, `mine=true`, `category`, `escalated=true`, `slaBreached=true`, `q` (search), `createdFrom`, `createdTo`; sort + pagination. Implement with JPA Specifications.
- `GET /tickets/{id}` — includes `allowedActions[]` and `slaRemainingSeconds`
- `PATCH /tickets/{id}` — edit subject/description/category/priority (+ `version`)
- `PUT /tickets/{id}/assignee` — `{agentId | null, version}`
- `POST /tickets/{id}/transitions` — `{targetStatus, note?, reason?, version}`
- `POST /tickets/{id}/reopen` — `{reason, version}`
- `POST /tickets/{id}/escalate` — `{reason}`
- `GET /tickets/{id}/comments`, `POST /tickets/{id}/comments` — `{body, type: COMMENT|RESOLUTION_NOTE}`
- `POST /tickets/bulk/assign` (LEAD/ADMIN, bonus)

**Dashboard:** `GET /dashboard/summary` (unresolved count, by status, by priority, SLA breached, escalated, my open tickets, unassigned), `GET /dashboard/trends?days=14` (created vs resolved per day), `GET /dashboard/agents-workload` (LEAD/ADMIN)

**Agents (ADMIN unless noted):** `GET /agents` (any authenticated user, minimal fields, for assignment dropdowns), `POST /agents`, `PATCH /agents/{id}`, `PATCH /agents/{id}/active`

**Admin/partners:** `GET/POST /admin/partners`, `POST /admin/partners/{id}/rotate-secret`, `PATCH /admin/partners/{id}/active`, `GET /admin/webhook-deliveries` (filters: partner, outcome, date)

**Webhook:** `POST /webhooks/partners/tickets` (HMAC only, no JWT)

**AI:** `POST /ai/triage` `{subject, description}`, `POST /tickets/{id}/ai/summary`, `POST /tickets/{id}/ai/reply-draft`, `PATCH /ai/insights/{id}/feedback` `{accepted}`

**Notifications:** `GET /notifications`, `PATCH /notifications/{id}/read`, `POST /notifications/read-all`, `GET /notifications/stream` (SSE, bonus)

**Ops:** `/actuator/health/{liveness,readiness}`, `/actuator/prometheus` (not public in prod)

---

## 10. AI features (Groq) — useful, safe and optional

Use Groq's OpenAI-compatible Chat Completions API. **Model name and base URL come from config** (`GROQ_API_KEY`, `GROQ_MODEL`, `GROQ_BASE_URL`); check Groq's docs for currently available models and pick a fast instruction-tuned one.

Features (each has a clear purpose):
1. **Smart triage** (create form): from subject + description, suggest `category`, `priority`, a one-line summary, sentiment and a short rationale. Shown as **suggestions the agent can accept or ignore** — never auto-applied. Purpose: faster, more consistent prioritisation.
2. **Thread summary** (ticket detail): summarise long comment timelines for a lead who is picking up an escalated ticket. Purpose: no need to read 30 comments.
3. **Reply / resolution-note draft** (ticket detail): draft a resolution note the agent edits before saving. Purpose: faster, more consistent resolutions.
4. **Webhook auto-triage (bonus):** if a partner ticket arrives without a category, run triage asynchronously and attach a suggestion for the agent (do not change the partner's priority).

Engineering rules:
- **Structured output:** request JSON and validate it against a schema; map priority/category **only to valid enum values**, else drop the suggestion. Never trust model output.
- **Prompt-injection safety:** ticket text is untrusted data. Wrap it in delimiters, instruct the model to treat it as data, and never let model output trigger actions on its own.
- **PII minimisation:** redact emails, phone numbers and card-like numbers before sending (`PiiRedactor`, unit-tested).
- **Resilience:** timeout (e.g. 8s), Resilience4j circuit breaker, per-user rate limit. If AI is unavailable or the key is missing, the feature hides gracefully; the core app works 100% without AI.
- Store results in `ticket_ai_insights` with model + `prompt_version`, plus accept/reject feedback (lets us measure usefulness).
- Prompts live in versioned files under `resources/prompts/`.

---

## 11. Edge cases (each needs a test; list them all in `docs/edge-cases.md` with the test name)

**Webhook / HMAC:** missing headers; malformed signature header; wrong secret; tampered body; whitespace-only change to body (must fail); expired timestamp; future timestamp; non-numeric timestamp; unknown or inactive partner; duplicate event id (sequential); duplicate event id (20 concurrent requests → exactly 1 row); same event id from two different partners (both allowed); valid signature + invalid priority (400); malformed JSON (400); oversized payload (413); rate limit (429); old secret inside / outside the rotation grace window; server started without an encryption key (fails fast).

**Lifecycle:** every allowed transition; every disallowed transition; same-status transition; resolve without a note; IN_PROGRESS without an assignee; each mutation on a CLOSED ticket (edit, assign, comment, escalate, transition) → 409; reopen a non-closed ticket → 409; reopen without a reason → 400; SLA recalculated on priority change; scheduler escalates once per level (idempotent across runs and instances).

**Concurrency:** two updates with the same version → one succeeds, one gets 409; two agents self-assigning the same ticket at once → one wins.

**Validation:** null/empty/lowercase/unknown priority; blank customer name; too-long description; HTML/script in comments rendered as text; unknown filter values (400); empty filter results (200, empty page); page size > 100 clamped or rejected (document the choice).

**Auth/RBAC:** wrong password; locked account; expired access token → refresh flow; refresh token reuse → family revoked; deactivated agent with a still-valid JWT → 401; AGENT trying to assign others / close / reopen / access admin APIs → 403 (test the full permission matrix with a parameterised test); last admin demote/deactivate → 409; deactivate an agent with open tickets → 409.

**Dashboard:** zero tickets → zeros, not errors; counts match the filtered list exactly.

**AI:** missing key → feature disabled; Groq timeout/5xx → graceful `AI_UNAVAILABLE`; model returns invalid JSON or an invalid enum → suggestion discarded; prompt-injection text in a ticket → no effect on system behaviour.

---

## 12. Frontend — design and experience

### 12.1 Quality bar
The polish, speed and consistency of top consumer products (Uber, Amazon, Flipkart): fast, predictable, accessible and consistent. **Do not copy their branding or assets**; create an original brand for this product (name, logo mark, palette). The brand is **Untangle** (see the header).

### 12.2 Visual theme: "Glass-Brutalism"
A deliberate mix of **neo-brutalism** (bold, honest, high-contrast) and **glassmorphism** (depth, translucency):
- **Brutalist structure:** 2–3px solid borders in near-black (light mode) / off-white (dark mode), **hard offset shadows** (`4px 4px 0 0`) with no blur, chunky radius (8–12px), bold display type (e.g. Space Grotesk / Clash Display / Archivo Black for headings, Inter or Geist for UI text, JetBrains Mono for ticket numbers and IDs).
- **Glass layers:** translucent panels (`backdrop-blur`, 8–16% fill) over a living 3D/gradient background — used for the shell, overlays, modals, KPI cards and the command palette.
- **The combination:** glass cards with brutalist hard borders and offset shadows; buttons that physically "press" (shadow collapses on active).
- **Semantic colours:** priority (`LOW` slate, `MEDIUM` blue, `HIGH` amber, `CRITICAL` red with a subtle pulse), status (`OPEN`, `IN_PROGRESS`, `RESOLVED`, `CLOSED`), SLA (on-track / at-risk / breached).
- **Light and dark mode**, with all tokens in `styles/tokens.css` (CSS variables) and mapped into Tailwind via `@theme`.
- **Accessibility first:** glass is risky for contrast, so text always sits on a sufficiently opaque surface. **WCAG AA contrast**, visible focus rings (brutalist outline), full keyboard navigation, ARIA on custom components, `prefers-reduced-motion` respected everywhere.

### 12.3 3D and motion (immersive, but never in the way of work)
Support agents use this tool all day, so **3D must enhance, not slow down**:
- **Login / landing:** a hero R3F scene — the Untangle knot straightening into a checkmark, with floating glass "ticket" cards, reacting to the cursor (parallax), with a React Bits text effect on the headline.
- **App background:** a subtle, low-cost animated background (React Bits backgrounds such as Aurora / Silk / Particles, or a light R3F scene) behind glass panels; paused when the tab is hidden; `frameloop="demand"` where possible.
- **Transitions:** route transitions with Motion `AnimatePresence`; **shared-element transition** (`layoutId`) from a ticket row to the detail page header; a 3D tilt/flip on KPI cards; an animated status stepper on the detail page; a satisfying micro-interaction when a ticket is resolved (e.g. a React Bits click spark/confetti, restrained).
- **Data views stay crisp:** tables, forms and timelines are not 3D; they get only subtle motion (staggered list entrance, skeleton loaders, optimistic updates).
- **Performance budget:** three.js and R3F are **lazy-loaded** in a separate chunk, never in the main bundle. Target LCP < 2.5s, CLS < 0.1, INP < 200ms on the dashboard. **WebGL fallback** (static gradient) if unsupported or low-power. Lighthouse ≥ 90 for performance and accessibility on the dashboard.

### 12.4 React Bits usage
Install components with the official CLI (TS + Tailwind variants) into `components/reactbits/`, then adapt them to our tokens. Candidates (verify exact names on reactbits.dev): animated backgrounds (Aurora, Silk, Particles, Galaxy), text effects (SplitText, BlurText, ShinyText, GradientText), **CountUp** for the unresolved counter, **SpotlightCard / TiltedCard / GlareHover** for KPI cards, **AnimatedList** for the timeline/notifications, **Dock** for navigation, **Magnet / ClickSpark** for primary actions, **Stepper** for the status flow. Use them with purpose and restraint, not everywhere.

### 12.5 Screens (each with its purpose)
1. **Login:** 3D hero + glass-brutal login card; error states for invalid credentials and locked account. *Purpose: secure entry.*
2. **Dashboard:** the giant **unresolved count** (CountUp) with a breakdown by priority; KPI cards (SLA breached, escalated, unassigned, my open tickets); a 14-day created-vs-resolved trend chart; agent workload (LEAD/ADMIN); a ticket table with **filters (priority, agent, status, category, escalated, SLA breached, search)** **synced to URL query params** (shareable, back-button friendly); saved quick views ("My open", "Unassigned critical", "Breached"); pagination; loading skeletons; empty and error states. *Purpose: what needs attention right now.*
3. **Ticket detail:** header with ticket number, priority, status, assignee, live SLA countdown, escalation badge; an **action bar driven by `allowedActions`** (disabled actions show a tooltip explaining why); status stepper; a timeline that mixes comments, resolution notes and system events; a comment composer (Markdown-free plain text, Ctrl+Enter to send); AI panel (summary, reply draft); a "closed" banner with a Reopen button for LEAD/ADMIN; a version-conflict dialog. *Purpose: work the ticket end to end.*
4. **Create / assign ticket form:** RHF + Zod (mirrors backend rules); category/priority pickers; a "Suggest with AI" button showing triage suggestions as accept/ignore chips; an assignee picker with agent workload counts; inline field errors from backend Problem Details. *Purpose: fast, correct ticket intake.*
5. **Kanban board (bonus):** columns by status; drag-and-drop calls the transitions API; invalid drops are prevented using `allowedActions` and snap back with a toast. *Purpose: visual queue management for leads.*
6. **Admin — Agents:** create agent, change role, activate/deactivate (with bulk-reassign flow). *Purpose: team management.*
7. **Admin — Partners & Webhooks:** create a partner, rotate its secret (shown once with a copy button and warning), view **webhook delivery logs** (outcome, reason, event id, linked ticket). *Purpose: debug partner integrations.*
8. **Notifications:** bell with unread count and a drawer (escalations, assignments, SLA breaches); SSE or polling. *Purpose: nothing urgent is missed.*
9. **Command palette (Ctrl/Cmd+K):** jump to a ticket by number, create a ticket, switch views; plus keyboard shortcuts (`c` create, `/` search, `g d` dashboard). *Purpose: power-user speed.*
10. **403, 404, 500, offline pages** and a global error boundary, styled in the theme.

### 12.6 Frontend engineering rules
- Route guards: `RequireAuth`, `RequireRole`; UI hides actions the user can't perform, but **the backend is the real enforcement**.
- TanStack Query with a query-key factory; optimistic updates for comments and assignment, with rollback on error.
- One API client; map Problem Details `code` to friendly messages in one place.
- Code-split by route; lazy-load the admin, kanban and 3D chunks.
- No `any`; strict TS; components small and composable; no business rules duplicated from the backend except form validation.

---

## 13. Documentation (separate docs are a deliverable)

```
docs/
├── README.md                          # index of all docs
├── problem-statement.md               # what & why, users, success criteria
├── assumptions-and-open-questions.md  # Section 2 table + answers when the lead replies
├── architecture.md                    # HLD, component + sequence diagrams (Mermaid), request flows
├── database.md                        # ER diagram (Mermaid), every table/column, relationships, constraints, index rationale
├── state-machine.md                   # diagram + transition table + RBAC per transition
├── api.md                             # endpoint overview, error codes, pagination; links to Swagger
├── security.md                        # threat model (STRIDE-lite), auth flow, RBAC matrix, HMAC design, secret storage
├── webhook-integration-guide.md       # for partners: headers, signing algorithm, examples in curl/Node/Java/Python, responses, retries
├── tradeoffs.md                       # every major tradeoff: options, chosen, gained, given up
├── decisions/                         # ADRs: ADR-000-template.md, ADR-001-..., one per decision
├── edge-cases.md                      # case → expected behaviour → test name
├── testing.md                         # strategy, pyramid, how to run, coverage
├── ai-features.md                     # purpose, prompts, safety, failure modes
├── frontend-design-system.md          # tokens, theme, components, motion & 3D rules, a11y
├── scalability.md                     # what we did now + how we'd scale 10x / 100x
├── observability.md                   # logs, metrics, health checks, dashboards
└── runbook.md                         # run locally, env vars, rotate secrets, common failures
```

ADR format: *Title, Status, Context, Options considered, Decision, Consequences (pros/cons), Date.*

Minimum ADRs: modular monolith + package-by-feature; JWT + refresh rotation; RBAC via permissions; DB unique constraint for idempotency; 200 vs 409 for duplicates; HMAC over `timestamp.rawBody`; generic webhook error bodies + delivery log; AES-GCM encrypted partner secrets; VARCHAR+CHECK vs PG ENUM; append-only comments as audit trail; optimistic locking; `allowedActions` from the server; SLA scheduler with ShedLock; Flyway; AI as suggestion-only; 3D lazy-loading and performance budget; Redis as optional.

Each doc should have a **"Simple explanation"** section (plain words) and a **"Technical details"** section.

---

## 14. Phased delivery plan

| Phase | Scope | Exit criteria |
|---|---|---|
| **0. Foundation** | Monorepo layout, `CLAUDE.md`, docker-compose (postgres, backend, frontend, redis profile), `.env.example`, Makefile, CI skeleton, docs skeleton + ADR template, backend & frontend scaffolds with lint/format | `make up` boots everything; CI green on an empty test |
| **1. Database** | Flyway `V1` schema with all constraints/indexes, entities, repositories, dev seed | Testcontainers repository tests pass; `database.md` + ER diagram done |
| **2. Security** | Auth (login/refresh/logout/me), JWT filter, refresh rotation + reuse detection, lockout, RBAC permissions, global error handler, correlation id | Auth + RBAC matrix tests pass |
| **3. Ticket core** | Create, get (with `allowedActions`), edit, assign, transitions, reopen, comments, filters + pagination, dashboard summary/trends, optimistic locking | Every lifecycle & validation edge case tested; Swagger complete |
| **4. Webhook + HMAC** | Cached-body filter, signature filter, partners + encrypted secrets + rotation, idempotent create, delivery log, rate limit, **partner simulator** with all scenarios | `make simulate` shows every scenario passing; concurrency test passes |
| **5. Escalation & notifications** | SLA policy, ShedLock scheduler, manual escalate, notifications (+ SSE bonus) | Scheduler tests (idempotent), notification tests |
| **6. AI** | Groq client, triage, summary, reply draft, PII redaction, circuit breaker, feedback | Works with a key; app fully works without a key; tests with a mocked Groq |
| **7. Frontend foundation** | Tokens, theme (light/dark), UI primitives, React Bits setup, app shell, router, auth flow with refresh, API client + generated types, error boundary | Login works end to end; Vitest set up |
| **8. Frontend screens** | Dashboard, ticket list + filters, detail, create/assign form, admin screens, notifications, command palette | All requirements usable end to end in the browser |
| **9. 3D, motion, polish** | R3F hero, background, transitions, micro-interactions, reduced-motion, WebGL fallback, a11y pass, Lighthouse | Lighthouse perf & a11y ≥ 90 on the dashboard |
| **10. Quality hardening** | Playwright E2E (login → create → assign → progress → resolve → close → reopen; RBAC UI; webhook ticket appears), JaCoCo ≥ 80% on service/domain, ArchUnit rules, k6 load test (bonus), security headers check | CI fully green |
| **11. Docs & demo** | Finalise all docs, README with screenshots/GIFs, `docs/demo-script.md` (a 10-minute walkthrough for my lead) | A reviewer can clone, `make up`, and demo in 5 minutes |

---

## 15. Scalability (bonus — implement the cheap wins, document the rest)

Implement now:
- Stateless JWT backend → horizontal scaling behind a load balancer.
- Pagination everywhere; indexes matched to the actual filter queries (verify with `EXPLAIN ANALYZE` and paste the results into `database.md`).
- HikariCP pool tuned; no N+1 queries (use fetch joins/entity graphs; add a test or a Hibernate statistics check).
- Dashboard summary cached in Redis with a short TTL (30s) + eviction on ticket changes (profile `redis`); in-memory fallback.
- Distributed rate limiting (Bucket4j + Redis) when the Redis profile is on.
- ShedLock so the scheduler is safe across multiple instances.
- k6 script: load the list/filter endpoint and the webhook endpoint; record results in `scalability.md`.

Document only (in `scalability.md`): read replicas for the dashboard, transactional outbox + message queue (Kafka/RabbitMQ) for async webhook processing and notifications, table partitioning by `created_at` for very large ticket volumes, CDN for the frontend, moving search to OpenSearch, multi-region considerations.

---

## 16. Observability
- Correlation ID (`X-Request-Id`) generated or propagated by a filter, put in MDC, returned in responses and in Problem Details `traceId`.
- JSON logs with level discipline (INFO for business events such as "ticket created/transitioned", WARN for rejected webhooks, ERROR for unexpected failures).
- Custom Micrometer metrics: `tickets_created_total{source}`, `ticket_transitions_total{from,to}`, `webhook_requests_total{outcome}`, `sla_breaches_total`, `ai_requests_total{feature,outcome}`, plus latency timers.
- Docker Compose `observability` profile: Prometheus + Grafana with a provisioned dashboard JSON in `infra/grafana/`.

---

## 17. Docker
- **Backend:** multi-stage build (Maven build → JRE 21 slim/distroless), non-root user, `HEALTHCHECK` on readiness, JVM container-aware flags.
- **Frontend:** multi-stage (node build → nginx:alpine), SPA fallback, gzip/brotli, cache headers for hashed assets, security headers/CSP in `nginx.conf`, `/api` reverse-proxied to the backend (avoids CORS in Docker).
- **Compose:** `postgres` (named volume, healthcheck), `backend` (depends_on healthy postgres), `frontend`, `redis` (profile), `partner-simulator` (profile `simulator`), `prometheus` + `grafana` (profile `observability`).
- Everything configured via `.env`; `make up` works on a fresh clone.

---

## 18. Definition of Done (for every feature)
- [ ] Meets the requirement end to end (API + UI + DB)
- [ ] Business rules enforced in the service layer, not just the UI
- [ ] Input validated; errors return correct Problem Details codes
- [ ] RBAC enforced and tested
- [ ] Unit + integration tests, including the relevant edge cases from Section 11
- [ ] No secrets or PII in logs
- [ ] Swagger/OpenAPI updated; frontend types regenerated
- [ ] Docs + ADR updated; `edge-cases.md` updated with test names
- [ ] Lint, type check, tests and Docker build pass in CI
- [ ] I (the intern) can explain it — include a short "How it works" note in the phase summary
