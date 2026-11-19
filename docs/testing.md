# Testing

## Simple explanation

A feature isn't done until it has tests. We have many small, fast tests for the rules, fewer tests
that run against a real database, and a handful of end-to-end browser tests for the critical
journeys. Every edge case from the brief gets a named test, listed in
[edge-cases.md](edge-cases.md).

## Technical details

### Test pyramid

| Layer | Tools | What it covers | Phase |
|---|---|---|---|
| Unit | JUnit 6, Mockito, AssertJ / Vitest, Testing Library | state machine, HMAC verifier, PII redactor, mappers, components | from 0 |
| Slice / integration | Spring Boot Test, MockMvc(`MockMvcTester`), **Testcontainers PostgreSQL** | repositories, constraints, controllers + security, idempotency under concurrency | from 1 |
| Architecture | ArchUnit | layering and feature boundaries | from 1 |
| API mocking (frontend) | MSW | screens against realistic API responses | from 7 |
| End to end | Playwright | login → create → assign → progress → resolve → close → reopen; RBAC UI; webhook ticket appears | 10 |
| Contract scenarios | partner simulator | every webhook scenario against a running stack | 4 |
| Load (bonus) | k6 | list/filter and webhook endpoints | 10 |
| Feature + RBAC walkthrough | `scripts/check-features.py` | every feature through the real API, plus a live role × action matrix | 10 |

### Current totals

| Suite | Command | Count | Needs |
|---|---|---|---|
| Backend unit + integration (incl. ArchUnit, coverage gate) | `make test-backend` | **417** | Docker (Testcontainers) |
| Frontend components, logic, accessibility (axe) | `make test-frontend` | **69** | — |
| Browser end-to-end (Playwright) | `make e2e` | **9** | `make up` |
| Feature walkthrough + RBAC matrix | `make check-features` | **103** checks | `make up` (demo data) |
| Partner webhook scenarios | `make simulate` | **13** | `make up` |
| Load | `make load` | 2 scripts | `make up` |
| Stress (find the breaking point) | `make stress` | 1 script, 6 rate steps | Docker; builds its own 200k-ticket stack |
| Lighthouse | `make lighthouse` | dashboard, mobile + desktop | `make up`, Chrome |

### Test data

| Where | What | Created by |
|---|---|---|
| Backend integration tests | each test creates its own agents/tickets/partners through helpers (`createAgent`, `createTicket`, `bearer(role)`) and truncates all tables and caches afterwards | `support/IntegrationTest` |
| Demo seed (`db/seed-demo/R__demo_data.sql`, profile `demo`) | 9 staff (1 admin, 2 leads, 6 agents), 60 tickets across every status, priority, category and SLA state, comments, 1 partner `acme-bank` | Flyway repeatable migration; `DemoSeedIntegrationTest` checks it |
| Frontend tests | realistic API objects in `src/test/fixtures.ts`; MSW handlers in `src/test/server.ts` (unhandled requests fail the test) | — |
| E2E / feature check / simulator / k6 | create their own uniquely tagged data on top of the demo seed (partners are disabled afterwards) | the scripts themselves |

Reset the local data at any time: `make nuke` then `make up`.

### How to run

```bash
make test            # backend (mvn verify) + frontend (vitest)
make test-backend    # coverage report: backend/target/site/jacoco/index.html
make test-frontend
make ci              # lint + typecheck + test + build, exactly like CI (minus Docker)
```

### Conventions

- Test names describe behaviour: `rejectsTransitionFromClosedToResolved`, not `test1`.
- Integration tests use Testcontainers (real PostgreSQL 18), never H2: constraint and concurrency
  behaviour must match production.
- Coverage gate (enforced by `mvn verify`): every `application`/`domain` package ≥ 80 % lines;
  whole backend ≥ 80 % lines and ≥ 65 % branches.

### Integration test setup (Phase 1)

- `support/TestcontainersConfiguration` starts `postgres:18.7-alpine3.24` (the same image as Compose)
  and wires it in with `@ServiceConnection`. No URLs in test config.
- `support/IntegrationTest` is the base class (`@SpringBootTest`, profile `test`). All subclasses
  share **one** cached Spring context and **one** container, so the whole DB suite runs in seconds.
- Transactional tests roll back automatically. Tests that need real commits (concurrency,
  optimistic locking) call `truncateAllTables()` in `@AfterEach`.
- `DemoSeedIntegrationTest` uses its own context (profiles `test,demo`), so it gets a fresh
  database with the seed applied.
- Docker must be running for `make test` (Testcontainers). CI runners have Docker.

### Phase 10 additions

- Backend (407): `DashboardCountsStampedeTest` (one computation for 20 concurrent virtual threads; no stale caching), `OverloadHandlingTest` (503 `SERVICE_BUSY`), `MetricsIntegrationTest` (scrape, counters, probes), `DashboardCacheIntegrationTest`,
  `RedisProfileIntegrationTest` (real Redis), `QueryCountIntegrationTest` (N+1 guard),
  `SlaEscalationSchedulerTest`.
- Frontend (56): `command-palette.test.tsx` (Ctrl+K focuses the search box, number jump) — added
  after the end-to-end feature check found the palette wasn't focusing its input.
- Playwright (14): lifecycle, RBAC ×3, webhook, security headers ×4, and the layout guard ×5
  (`frontend/e2e`).

### Layout guard (`e2e/layout.spec.ts`)

**Simple explanation:** a robot opens every screen as admin, lead and agent, at phone (375 px),
tablet, laptop and desktop sizes. It fails if anything sticks out sideways, if one control covers
another, if a floating button sits on top of a card, or if a heading is cut off. It also opens the
menu drawer, Appearance menu, notifications, search and the edit dialog on a phone and checks they
fit on screen.

**Technical details:** an in-page audit scrolls each page in steps and runs:
- `scrollWidth` against the screen width, naming the widest offenders
- `elementFromPoint` at the centre of every visible link, button and field. Hidden elements are
  skipped: sr-only content, collapsed `<details>`, `aria-hidden`, and anything off screen.
- absolute or fixed controls against `.solid-panel` / `.glass-panel` boxes they don't belong to
- `h1`/`h2` scroll width against their box

Pop-ups are measured once their animation has finished. A run on the old build caught the login
palette icon sitting on the card at phone size; it is fixed.

### Verifying end to end without touching your data

Run the checks on a throwaway copy of the stack (its own database and ports):

```bash
FRONTEND_HOST_PORT=3200 BACKEND_HOST_PORT=8280 POSTGRES_HOST_PORT=5436 docker compose -p untangle-verify up -d --build --wait
```
```bash
CHECK_BASE_URL=http://localhost:3200 python3 -I scripts/check-features.py
```
```bash
FRONTEND_HOST_PORT=3200 BACKEND_HOST_PORT=8280 POSTGRES_HOST_PORT=5436 docker compose -p untangle-verify --profile simulator run --rm partner-simulator --scenario all
```
```bash
cd frontend && E2E_BASE_URL=http://localhost:3200 npx playwright test
```
```bash
docker compose -p untangle-verify down -v
```

Run the simulator before the checker on a fresh stack: the checker's "webhook log readable" check
needs at least one delivery. Last run (2026-11-18): 103/103 checks, 13/13 webhook scenarios,
14/14 Playwright.
- `scripts/check-features.py` (103 checks): every feature by API plus the live RBAC matrix.

### Phase 9 additions (frontend total: 55 tests)

- `src/test/a11y.test.tsx`: axe-core on the app shell + 9 screens with realistic data (10).
- `hero-support.test.ts`: the 3D hero falls back under reduced motion, without WebGL and on small
  screens (4).
- New jsdom stub: `Element.scrollIntoView` (cmdk).
- `make lighthouse`: Lighthouse mobile + desktop on the signed-in dashboard of the running stack
  (results in [phase-9](how-it-works/phase-9.md)).

### Phase 8 additions (frontend total: 41 tests)

- `ticket-logic.test.ts`: URL filter round-trip, invalid values dropped, quick-view detection,
  board move → action mapping, close-needs-reason rule, SLA on-track / at-risk / breached / stopped.
- `ticket-detail.test.tsx`: action bar from `allowedActions` (denied ones disabled with the reason
  as their accessible description), HTML in comments rendered as text, resolve requires a note and
  sends the loaded `version`, `VERSION_CONFLICT` → conflict dialog → reload, closed ticket is
  read-only with Reopen, optimistic comment rolls back and returns the text.
- `screens.test.tsx`: list filters ↔ URL ↔ API query, empty state clears filters, dashboard KPIs
  link to filtered lists and workload is hidden from agents, create-form validation, AI chips
  accept/ignore per field, server field errors shown inline, agents can only self-assign.
- `admin.test.tsx`: rotated secret shown once and gone after closing, partner code validation,
  deactivate → `AGENT_HAS_OPEN_TICKETS` → bulk reassign → deactivate.
- New jsdom stubs: `ResizeObserver`, `matchMedia`. Fixtures in `src/test/fixtures.ts`.
- These tests found a real bug: every dialog used the id `dialog-title`, so on pages with several
  dialogs screen readers announced the wrong title. Dialogs now use `useId()`.

### Phase 7 additions (frontend: 15 tests)

- Stack: Vitest 5 (jsdom) + Testing Library + MSW 3. `src/test/server.ts` holds the MSW server,
  realistic fixtures (`leadAgent`, `tokenResponse`) and `problem()` for RFC 9457 bodies;
  `src/test/render.tsx` renders routes in a memory router with a fresh query cache.
- MSW 3 renamed `onUnhandledRequest` to `onUnhandledFrame`; we use `'error'` so an unmocked call
  fails the test instead of reaching the network.
- jsdom gaps are stubbed in `src/test/setup.ts`: `IntersectionObserver` (reports visible) and
  `HTMLDialogElement.showModal/close`.
- Covered: single-flight token refresh (3 concurrent 401s → 1 refresh), sign-out on failed refresh,
  Problem Details → `ApiError`, login validation / success / wrong credentials / locked account,
  `RequireAuth` and `RequirePermission`, `Field`/`Button` accessibility wiring.

### Phase 6 additions (backend total: 392 tests)

| Class | Tests | Proves |
|---|---|---|
| `PiiRedactorTest` | 10 | emails, cards (3 formats), long numbers, phones redacted; ordinary numbers kept |
| `AiIntegrationTest` | 9 | against a **fake Groq HTTP server**: validated suggestions + provenance, PII never sent, invalid enum dropped, invalid JSON discarded, 5xx and timeout → 503, circuit opens and stops calling, prompt injection has no effect, summary + feedback |
| `AiDisabledIntegrationTest` | 1 | no key → disabled, 503, core app works |

### Phase 5 additions (backend total: 372 tests)

| Class | Tests | Proves |
|---|---|---|
| `SlaEscalationIntegrationTest` | 6 | level formula and cap, idempotent re-runs, two concurrent runs → once per ticket, manual level kept, breach notifications, ShedLock exclusivity |
| `NotificationIntegrationTest` | 3 | assignment and escalation fan-out, no self-notification, read / read-all, others' → 404 |

### Phase 4 additions (backend total: 363 tests + 13 simulator scenarios)

| Class / tool | Tests | Proves |
|---|---|---|
| `WebhookCryptoTest` | 19 | HMAC vs an OpenSSL reference vector; any byte/timestamp/secret change fails; strict header format; AES-GCM IV freshness, AAD binding, tamper/wrong-key detection; fail-fast key |
| `WebhookIntegrationTest` | 19 | every §11 webhook case over HTTP, incl. 20 concurrent retries → 1 ticket, rotation grace in/out, delivery log reasons |
| `PartnerAdminIntegrationTest` | 5 | secret shown once and stored encrypted; rotation; duplicate/malformed codes; admin-only |
| **Partner simulator** (`make simulate`) | 13 scenarios | an independent Node implementation of the contract against the running stack, through nginx; also runs in CI |

### Phase 3 additions (backend total: 320 tests)

| Class | Tests | Proves |
|---|---|---|
| `TicketStateMachineTest` | 22 | the §5.1 table (16 pairs) and reason/note rules |
| `TicketAccessPolicyTest` | 37 | role × ownership × status → allowed or exact reason code (pure unit test, no Spring) |
| `TicketLifecycleIntegrationTest` | 42 | create/validate, full lifecycle, every rule violation, closed immutability, versions, concurrent self-assign, comments, escalation, bulk assign |
| `TicketQueryIntegrationTest` | 26 | every filter, search escaping, severity sort, paging metadata, invalid parameters |
| `DashboardIntegrationTest` | 4 | zeros, counts = list totals, trends with gaps, workload permission |
| `EndpointAuthorizationMatrixTest` | +12 | ticket and dashboard endpoints in the role matrix |

### Phase 2 additions (backend total: 177 tests)

| Class | Tests | Proves |
|---|---|---|
| `AuthIntegrationTest` | 21 | login, cookie flags, lockout and expiry, rate limit, refresh rotation and reuse detection, logout, expired/tampered/foreign tokens, deactivation and role changes applying immediately |
| `AgentAdminIntegrationTest` | 14 | create/validate agents, `EMAIL_TAKEN`, `LAST_ADMIN`, `CANNOT_DEACTIVATE_SELF`, `AGENT_HAS_OPEN_TICKETS`, listings |
| `EndpointAuthorizationMatrixTest` | 22 | role × endpoint over HTTP (403s come before body validation); anonymous → 401 |
| `RolePermissionsTest` | 22 | the §6.2 table cell by cell; roles cumulative; configurable close-own |
| `ErrorHandlingIntegrationTest` | 7 | Problem Details shape, `traceId`, no internals, 404/405, request-id hygiene, security headers |
| `SecurityPropertiesTest` | 7 | fail-fast on missing/short/non-base64 JWT secret; secret never printed |
| `BootstrapAdminRunnerTest` | 5 | first admin only when none exists; never takes over an existing account (Mockito) |
| `CommonUtilitiesTest` | 7 | email masking, token-bucket limiter |

HTTP tests use `MockMvcTester` (AssertJ-style MockMvc) through the full security filter chain
against the real database. Helpers in `IntegrationTest`: `createAgent(role)`, `bearer(role)`,
`login(...)`, `refresh(cookie)`, `json(result, path)`, `body(result)`.

### Phase 1 suite (72 backend tests)

| Class | Tests | Proves |
|---|---|---|
| `SchemaConstraintsIntegrationTest` | 33 | every CHECK/unique/trigger rule rejects bad data (raw SQL, bypassing Java) |
| `RepositoryMappingIntegrationTest` | 13 | every entity round-trips (enums, JSONB, generated ticket numbers, UUIDv7); repository queries work |
| `ConcurrencyIntegrationTest` | 2 | 20 concurrent duplicate inserts → 1 row; stale version → rejected |
| `DemoSeedIntegrationTest` | 5 | demo data loads and covers every state |
| `ArchitectureTest` | 15 | layering, private repositories, no Spring in domain, entities extend `BaseEntity`, no field injection |
| `UntangleApplicationTests` | 4 | app starts against real Postgres; probes up; no stack traces |

### Phase 0 baseline

| Test | Proves |
|---|---|
| `UntangleApplicationTests.contextLoads` | Spring context starts |
| `UntangleApplicationTests.livenessProbeIsUp` / `readinessProbeIsUp` | Docker healthchecks have a working target |
| `UntangleApplicationTests.errorResponsesNeverExposeStackTraces` | Section 6.3: no stack traces leak |
| `App.test.tsx` (3 tests) | React, Tailwind and the test setup work; heading and region are accessible |
