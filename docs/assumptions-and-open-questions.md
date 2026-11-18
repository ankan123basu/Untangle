# Assumptions and open questions

## Simple explanation

The brief didn't say everything. Where something was unclear, we **picked a sensible default,
implemented it, and wrote it down here** so the lead can confirm or change it. Each default is
kept configurable or isolated in one place, so changing it later is cheap.

When the lead answers, fill in the **Lead's answer** column and link the commit that applied it.

## Technical details

### A. Product defaults from the brief (MASTER_PROMPT Section 2)

| # | Question | Default we implement | Where it lives | Lead's answer |
|---|---|---|---|---|
| A1 | Allowed status transitions | State machine in [state-machine.md](state-machine.md) | `TicketStateMachine` | |
| A2 | Where does a reopened ticket go? | `CLOSED → OPEN`, keeps previous assignee (see R5), `reopened_count++`, reason required | `TicketService.reopen` | |
| A3 | Comments on CLOSED tickets? | Not allowed (closed = immutable until reopened) | closed-ticket guard | |
| A4 | Resolution note required to resolve? | Yes, non-empty | `TicketStateMachine` | |
| A5 | What is "unresolved"? | `OPEN` + `IN_PROGRESS` | dashboard query | |
| A6 | Priority values | `LOW`, `MEDIUM`, `HIGH`, `CRITICAL` | `Priority` enum + DB CHECK | |
| A7 | Category values | `BILLING`, `TECHNICAL`, `ACCOUNT`, `REFUND`, `GENERAL`, `OTHER` | `Category` enum + DB CHECK | |
| A8 | Duplicate webhook response | `200 OK` + existing ticket id + `"duplicate": true` | webhook controller | |
| A9 | Event id uniqueness scope | Per partner: `UNIQUE(partner_id, external_event_id)` | DB constraint | |
| A10 | Auth / roles | JWT; roles `ADMIN`, `LEAD`, `AGENT` | `security` package | |
| A11 | Escalation meaning | SLA-based automatic + manual (see R4) | `escalation` package | |
| A12 | Agent onboarding | No sign-up; ADMIN creates agents; first admin from env vars | bootstrap runner | |
| A13 | Can agents be deleted? | Never; deactivate only | `AgentService` | |

### B. Ambiguities found during Phase 0 review, and how we resolved them

| # | Issue | Resolution | Phase |
|---|---|---|---|
| R1 | Spec lists an `ApiResponse` wrapper **and** RFC 9457 errors | Success = plain DTOs (`PageResponse` for lists); errors = RFC 9457 only. [ADR-007](decisions/ADR-007-response-and-error-format.md) | 3 |
| R2 | Per-partner rate limit runs *after* HMAC verification, so an unsigned flood still costs CPU | Add a cheap **per-IP** limit before header checks; keep per-partner limit at step 6. 413 uses `Content-Length` **and** a bounded stream read (never buffer > 64 KB) | 4 |
| R3 | Same event id re-sent with a **different** body | Return the existing ticket (A8); delivery log records `payload_sha256 mismatch` so admins notice | 4 |
| R4 | "Escalated for that level" isn't defined | Level 1 at SLA due; level 2 at 2× SLA; level 3 at 3× SLA (cap configurable). Manual escalation adds +1. On reopen, `sla_due_at` is recalculated from the reopen time | 5 |
| R5 | A2 (keep assignee) vs a since-deactivated assignee | If the previous assignee is inactive, the reopened ticket becomes unassigned | 3 |
| R6 | "Own tickets" for AGENT edits | Own = currently **assigned** to the agent (not the creator) | 2–3 |
| R7 | Which mutations carry `version`? | Edit, assign, transition, reopen: **required**. Comment and escalate: not required (append-only / atomic increment, nothing to lose) | 3 |
| R8 | Deactivated agent's existing JWT | Active-flag cache TTL ≤ 30 s; evicted immediately on the instance that deactivates. Max exposure documented in security.md | 2 |
| R9 | `Secure` refresh cookie over plain HTTP in dev | Works on `localhost` in Chrome/Firefox; same origin via nginx/Vite proxy. `AUTH_COOKIE_SECURE` env flag for Safari/plain-HTTP dev | 2 |
| R10 | Groq receives ticket text (fintech context) | AI **off by default** (`AI_ENABLED=false`), PII redacted before sending. **Open question for the lead:** is sending redacted ticket text to a third-party LLM acceptable? | 6 |
| R11 | Lighthouse ≥ 90 vs a 3D dashboard | Phase 9: R3F only on the login page. Revised by ADR-025: signed-in pages also get an ambient 3D layer, mounted after load + idle, 30 fps on demand, paused when hidden, off on phones / reduced motion / user switch. Dashboard still 99–100 | 9, ADR-025 |
| R12 | Dev seed vs A12 (admin from env vars) | First admin created by a Java startup runner from env vars (Phase 2). Demo data is SQL under `db/seed-demo`, enabled by the `demo` profile (included by `dev`, default in `.env.example` for Docker demos), with a pre-computed BCrypt hash. **Done in Phase 1** | 1–2 |
| R13 | Simulator language | Node.js + TypeScript (closest to a real external partner; built-in `crypto` HMAC). ADR in Phase 4 | 4 |
| R14 | Comments on RESOLVED tickets? | Allowed. Only CLOSED is frozen | 3 |

### C. Environment decisions (agreed with the developer in Phase 0)

| Decision | Why | Record |
|---|---|---|
| Java package root `dev.untangle` | Personal project; no employer names in code | MASTER_PROMPT header |
| Java 21 LTS + Spring Boot 4.1.2 | Spec says 21 and it's the installed JDK; Boot 4.1 is current stable | [ADR-002](decisions/ADR-002-java-21-spring-boot-4.md) |
| PostgreSQL 18 (spec said 16) | Current stable; matches the developer's local install and pgAdmin. One-line change if 16 is required | [tech-stack.md](tech-stack.md) |
| Docker Postgres on host port 5433 | Local Homebrew Postgres already owns 5432 | `.env.example` |
| Local toolchain + Docker runtime | Faster feedback; reviewers still only need Docker for `make up` | [ADR-004](decisions/ADR-004-local-toolchain-docker-runtime.md) |
| TypeScript 6.0.x, not 7.x | typescript-eslint supports `<6.1` | [ADR-006](decisions/ADR-006-frontend-toolchain.md) |
| UUIDv7 primary keys instead of `gen_random_uuid()` (v4) | time-ordered inserts; PG 18 native | [ADR-008](decisions/ADR-008-uuidv7-primary-keys.md) |
| Extra DB invariants: IN_PROGRESS ⇒ assignee, RESOLVED ⇒ `resolved_at`, MANUAL ⇔ creator, comments append-only (trigger) | defence in depth beyond §8's list | [database.md](database.md) |
| `ticket_ai_insights.ticket_id` nullable for `TRIAGE` | triage runs on the create form before the ticket exists | [database.md](database.md) |
| JWT via Spring OAuth2 Resource Server (Nimbus, HS256), not jjwt | first-party bearer filter; nothing security-critical hand-rolled | [ADR-013](decisions/ADR-013-jwt-and-refresh-rotation.md) |
| Locked account returns **423** (`AUTH_ACCOUNT_LOCKED`) | distinct UI state required by §12.5; revealed only after 5 wrong passwords | [security.md](security.md) |
| Extra error codes: `AUTH_UNAUTHENTICATED`, `AUTH_ACCOUNT_DISABLED`, `AUTH_REFRESH_INVALID`, `EMAIL_TAKEN`, `CANNOT_DEACTIVATE_SELF`, `NOT_FOUND`, `METHOD_NOT_ALLOWED`, `UNSUPPORTED_MEDIA_TYPE`, `INTERNAL_ERROR` | §9's list had no code for these situations | [api.md](api.md) |
| Admin agent list at `GET /api/v1/admin/agents`; `GET /api/v1/agents` is the minimal dropdown list | one response type per endpoint (clean generated types) | [api.md](api.md) |
| Concurrent double-refresh → `AUTH_REFRESH_INVALID`, not "reuse" | a tab race isn't theft; only a token presented after its rotation committed revokes the family | [ADR-013](decisions/ADR-013-jwt-and-refresh-rotation.md) |
| No `logstash-logback-encoder` | Boot 4 has native structured JSON logging | [observability.md](observability.md) |
| MapStruct deferred to Phase 3 | Phase 2 views are 3–9 field records with `of(entity)` factories; MapStruct earns its keep with ticket DTOs | [tech-stack.md](tech-stack.md) |
| `TicketAccessPolicy` (ownership) moved to Phase 3 | it needs the ticket endpoints and `allowedActions` it serves | [ADR-014](decisions/ADR-014-rbac-via-permissions.md) |
| `sla_started_at` column (V2) | priority edits recompute from the same start; reopen restarts the clock | [database.md](database.md) |
| Reopen resets `escalation_level` to 0 | a new SLA period begins, so scheduler levels start over (R4) | [state-machine.md](state-machine.md) |
| Escalation only for OPEN/IN_PROGRESS | escalating resolved work has no meaning | `TicketAccessPolicy` |
| Unassigning only allowed for OPEN tickets | IN_PROGRESS/RESOLVED work must have an owner (DB CHECK too) | `Ticket.assignTo` |
| Page size > 100 → 400 (rejected, not clamped) | §11 asked to document the choice; clients learn instead of silently getting fewer rows | [api.md](api.md) |
| Comments stored verbatim (no server-side HTML stripping) | no data loss; the API serves JSON with `nosniff`; the UI must render as text (lint rule + Phase 8 test) | [api.md](api.md) |
| `deniedActions[{action, reason}]` alongside `allowedActions` | lets disabled buttons explain themselves (§12.5 tooltips) | [ADR-015](decisions/ADR-015-allowed-actions-from-server.md) |
| Extra endpoint `GET /tickets/by-number/{n}` | command palette "jump to UNT-…" (§12.5) | [api.md](api.md) |
| Webhook: per-IP limit (300/min) before header checks, per-partner limit after HMAC (R2) | unsigned floods cost no crypto work | [security.md](security.md) |
| Webhook body cap enforced by a filter (Content-Length + bounded read) | never buffer more than 64 KB | ADR-017 |
| Simulator creates its own throwaway partners via the admin API | results never depend on leftovers; demo partner `acme-bank` stays for curl examples | ADR-019 |
| Demo partner secret set at startup (`DemoPartnerSecretInitializer`, demo profile only) | the SQL seed can't encrypt with an env key | [webhook-integration-guide.md](webhook-integration-guide.md) |
| `PARTNER_NOT_FOUND`, `PARTNER_CODE_TAKEN` error codes | partner admin | [api.md](api.md) |
| Escalation levels (R4) implemented as `min(3, floor(elapsed / target))` | level 1 at the deadline, 2 at 2×, 3 at 3× | [ADR-020](decisions/ADR-020-sla-scheduler-with-shedlock.md) |
| Notifications and live screens via polling (15 s / 10 s / 20 s) instead of SSE | `EventSource` can't send the bearer header; no token-in-URL | [ADR-021](decisions/ADR-021-notifications-polling-over-sse.md) |
| Extra endpoint `GET /notifications/unread-count` | cheap bell badge for polling | [api.md](api.md) |
| Default Groq model `openai/gpt-oss-20b`, configurable via `GROQ_MODEL` | sources disagreed on Groq's live list (Nov 2026); verify with `GET /models` before deploying | [ai-features.md](ai-features.md) |
| Resilience4j used programmatically (core circuit breaker), not via annotations | no AOP starter or extra auto-config; one breaker, one client | ADR-022 |
| Webhook auto-triage (bonus) not built | partner payloads require `category`, so there's nothing to fill | [ai-features.md](ai-features.md) |
| AI sends no customer name/email and at most the last 40 timeline entries | data minimisation; bounded prompt size | [ai-features.md](ai-features.md) |
| `MENTION` notification type defined but unused | @mentions aren't in scope; kept in the CHECK for forward compatibility | [database.md](database.md) |
| Malformed `X-Event-Id` logged as `REJECTED_VALIDATION` (still a generic 401) | the outcome enum has no header-specific value; the reason text is precise | ADR-018 |

### D. Still open (for the lead)

1. R10: is sending **redacted** ticket text to Groq acceptable for a fintech support tool?
2. A3: should leads be able to add an internal note to a CLOSED ticket without reopening it?
3. Should the duplicate-with-different-payload case (R3) also alert an admin, not only log it?
