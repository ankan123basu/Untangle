# Trade-offs

## Simple explanation

Every design choice gives something up. This page lists the important choices in Untangle, what we
picked, what we said no to, and what it costs us, so a reviewer can see they were deliberate.

## Technical details

Each row links to its ADR where one exists.

### Architecture

| # | Chose | Over | Why | What it costs |
|---|---|---|---|---|
| 1 | **Modular monolith**, package-by-feature ([ADR-001](decisions/ADR-001-modular-monolith-package-by-feature.md)) | microservices | one team, one database, transactional consistency, one deploy | boundaries rely on discipline; ArchUnit tests guard them |
| 2 | **Two entry doors, one `TicketService`** | separate webhook path | rules (priority, closed guard, duplicates) can't drift between channels | the service must handle both "agent" and "partner" actors |
| 3 | **Synchronous webhook processing** | queue + async worker | the partner gets a definite 201/200 immediately; no broker to run | a burst is limited (429) rather than buffered; outbox + queue is the documented next step ([scalability.md](scalability.md)) |
| 4 | **Redis optional** ([ADR-005](decisions/ADR-005-redis-optional.md)) | Redis required | `make up` needs nothing extra; correctness never depends on a cache | per-instance caches/limits unless the `redis` profile is on |

### Data

| # | Chose | Over | Why | What it costs |
|---|---|---|---|---|
| 5 | **Idempotency by DB unique index** ([ADR-012](decisions/ADR-012-db-unique-constraint-for-idempotency.md)) | "seen events" set in memory/Redis | correct under concurrency, restarts and multiple instances | a duplicate costs one failed insert and a lookup |
| 6 | **Optimistic locking** ([ADR-011](decisions/ADR-011-optimistic-locking.md)) | row locks | no waiting; most edits don't collide | users occasionally see "this ticket changed" and reload |
| 7 | **Append-only comments** ([ADR-010](decisions/ADR-010-append-only-comments.md)) | editable comments | a trustworthy audit trail | typos stay; a correction is a new comment |
| 8 | **Close, never delete tickets** | hard delete | history and metrics stay complete | the table only grows (partitioning documented) |
| 9 | **UUIDv7 keys** ([ADR-008](decisions/ADR-008-uuidv7-primary-keys.md)) | bigint sequence / UUIDv4 | not guessable, time-ordered (good index locality), safe to generate anywhere | 16 bytes per key; human-friendly `UNT-000123` numbers kept separately |
| 10 | **Enums as varchar + CHECK** ([ADR-009](decisions/ADR-009-varchar-check-for-enums.md)) | PostgreSQL enum types | adding a value is a simple migration | slightly larger storage |
| 11 | **Flyway SQL, Hibernate validate** ([ADR-003](decisions/ADR-003-flyway-migrations.md)) | `ddl-auto=update` | reviewed, repeatable schema changes | migrations are written by hand |

### Security

| # | Chose | Over | Why | What it costs |
|---|---|---|---|---|
| 12 | **JWT + rotating refresh cookie** ([ADR-013](decisions/ADR-013-jwt-and-refresh-rotation.md)) | server sessions | stateless API, horizontal scaling | revocation needs the refresh-token table and a 30 s principal cache (R8) |
| 13 | **Access token in memory** | localStorage | XSS can't read a stored token | a silent refresh on each page load |
| 14 | **Permissions, not role checks** ([ADR-014](decisions/ADR-014-rbac-via-permissions.md)) | `hasRole` everywhere | roles can change without touching endpoints | one more mapping table to maintain |
| 15 | **Server-computed `allowedActions`** ([ADR-015](decisions/ADR-015-allowed-actions-from-server.md)) | UI re-implements rules | one source of truth; disabled buttons explain why | a slightly bigger detail payload |
| 16 | **Generic 401 for every webhook failure** ([ADR-018](decisions/ADR-018-generic-webhook-errors-and-delivery-log.md)) | detailed errors | attackers learn nothing | partners need the admin's delivery log to debug |
| 17 | **HMAC over `timestamp.rawBody`** ([ADR-017](decisions/ADR-017-hmac-over-timestamp-and-raw-body.md)) | JSON-canonical signing | no ambiguity about formatting; replay window | the raw bytes must be kept before parsing (filter) |

### Product and frontend

| # | Chose | Over | Why | What it costs |
|---|---|---|---|---|
| 18 | **Polling for live updates** (bell 15 s, open ticket 10 s, lists 20 s; [ADR-021](decisions/ADR-021-notifications-polling-over-sse.md)) | SSE / WebSockets | works through any proxy, no sticky connections, pauses in hidden tabs | changes appear within seconds, not instantly |
| 19 | **AI suggestion-only** ([ADR-022](decisions/ADR-022-ai-suggestion-only.md)) | AI auto-applies | a wrong or manipulated answer can't change data | an extra click to accept |
| 20 | **Filters in the URL** | component state | shareable, back button works | URL parsing must reject bad values |
| 21 | **Exact OpenAPI contract** ([ADR-023](decisions/ADR-023-exact-api-contract-and-vendored-react-bits.md)) | hand-written TS types | types can't drift from the backend | `@Nullable` must be remembered on new optional fields |
| 22 | **3D only on the desktop login; CSS motion elsewhere** ([ADR-024](decisions/ADR-024-motion-and-3d-budget.md)) | 3D backgrounds everywhere | the work screens stay fast (Lighthouse 91–100) | less spectacle inside the app |
| 23 | **Dashboard cache 30 s, evicted on every change** | no cache / long TTL | fast dashboard, never stale after a change | SLA-breached count can lag up to 30 s when a deadline simply passes |
