# CLAUDE.md — Untangle

Support ticket and escalation platform. **Full spec: `MASTER_PROMPT.md`** (its header lists agreed
deviations). Brand: Untangle, "Every complaint, untangled.", ticket ids `UNT-000123`.

## Working agreement (strict)
- Work **phase by phase** (MASTER_PROMPT §14). Start each phase with a short plan. End it by running
  `make ci` (+ `make up` when Docker files change), summarising, listing deviations, writing
  `docs/how-it-works/phase-N.md`, then **STOP and wait for "continue"**.
- Never silently change scope or a decision: raise it, propose an alternative, and record it in an
  ADR (`docs/decisions/`, template ADR-000) or in `docs/assumptions-and-open-questions.md`.
- **Verify library APIs** against installed jars/sources or official docs. Spring Boot 4 differs
  from Boot 3 (modular starters, moved test packages, Jackson 3).
- Tests are part of done. Each MASTER_PROMPT §11 edge case gets a named test in `docs/edge-cases.md`.
- Docs change in the **same commit** as the code. Every doc has "Simple explanation" + "Technical details".
- Conventional Commits, one logical change per commit. No employer names anywhere.

## Commands
```
make up | down | logs | ps | db-shell     # Docker stack (frontend :3000, backend :8080, postgres :5433)
make test | test-backend | test-frontend  # tests
make lint | typecheck | fmt | build | ci  # quality gates (ci = what GitHub Actions runs, minus Docker)
make backend-run | frontend-dev           # local dev (Vite :5173 proxies /api -> :8080)
```
On macOS the Makefile pins `JAVA_HOME` to JDK 21 (Homebrew Maven otherwise uses JDK 27).
Outside make: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`.

## Stack (exact pins in docs/tech-stack.md)
Java 21, Spring Boot 4.1.2, PostgreSQL 18, Flyway (`ddl-auto=validate`). React 19.3, Vite 8,
TypeScript **6.0.3** (not 7: typescript-eslint), Tailwind v4 (`@theme` in CSS, no JS config),
ESLint 10 flat config `strictTypeChecked`, Vitest 5. Node 26.

## Architecture
- Modular monolith, **package-by-feature** under `dev.untangle`: `auth agent ticket comment webhook
  escalation notification dashboard ai` + shared `common config security`.
  Every feature uses the same sub-packages: `api` (controllers, DTOs) → `application` (services)
  → `domain` (entities, enums, rules; no Spring) / `infrastructure` (repositories, private to the
  feature). Entities never reach `api`. ArchUnit enforces all of this (`ArchitectureTest`).
- Persistence: Flyway SQL (`db/migration`, never edit an applied file), Hibernate `validate`.
  UUIDv7 ids via `BaseEntity`; `AuditedEntity` adds timestamps + `@Version`. Entities have no public
  setters (factories and behaviour methods only), Lombok `@Getter` only. Invariants are mirrored as
  named DB constraints. Demo data: `db/seed-demo` (profile `demo`, included by `dev`; password
  `Untangle@2026`).
- Tests touching the DB or HTTP extend `support/IntegrationTest` (Testcontainers Postgres 18,
  `MockMvcTester`, shared context; helpers `bearer(role)`, `createAgent`, `json`, `body`).
  HTTP tests commit, so they `truncateAllTables()` in `@AfterEach`.
- Security: JWT via Spring resource server (HS256, `JWT_SECRET` required, fail-fast). Principal =
  `AuthenticatedAgent`, loaded from DB per request (30 s cache, evicted via `AgentAccessChangedEvent`
  after commit). Authorise with permissions (`security.rbac.Permission`, `RolePermissions`), never
  role names: URL rules in `SecurityConfig` for admin paths + `@PreAuthorize("hasAuthority('X')")`.
- Errors: throw `ApiException(ErrorCode.X, detail)`; `GlobalExceptionHandler` renders RFC 9457 with
  `code` + `traceId`. Never echo rejected values; mask emails in logs (`Masking.email`).
- Tickets: every mutation in `TicketService` follows load → `ensureNotClosed` → `checkVersion` →
  `TicketAccessPolicy.require` → entity behaviour method → `Timeline.system(...)` → event. Never add
  setters to `Ticket`; add a behaviour method. Filters = `TicketSpecifications` (reuse them for any
  count). DTO mapping via MapStruct (`TicketMapper`, unmapped targets fail the build).
- Webhook: `WebhookBodyLimitFilter` (64 KB, caches raw bytes) → `WebhookService` pipeline (§7.2
  order; every exit records a `webhook_deliveries` row via `DeliveryRecorder`, REQUIRES_NEW) →
  `TicketService.createFromWebhook` (idempotent via unique index). Partner secrets only through
  `SecretCipher` (AES-GCM, AAD = partner code). 401s stay generic. Simulator: `partner-simulator/`
  (Node 26 runs .ts directly; `make simulate`).
- Scheduled jobs: logic in a plain service method taking `now` (testable), trigger in a
  `@Scheduled` + `@SchedulerLock` component, disabled in tests (`app.escalation.scheduler-enabled`).
  Notifications are `@TransactionalEventListener` + `REQUIRES_NEW` on ticket events.
- AI: optional (`AI_ENABLED` + `GROQ_API_KEY`). `AiAssistant` = redact → wrap in `<ticket_data>` →
  `LlmClient` (Groq, timeout + circuit breaker) → validate every field → store insight. Never act on
  model output. Tests use a fake HTTP server, never the real provider.
- Cross-feature calls go through the other feature's `application` services or events, never its
  repositories; keep feature dependencies one-way (auth → agent, agent → ticket).
- Two entry doors, one brain: JWT REST and HMAC webhook both call `TicketService`.
- Success = plain DTOs (`PageResponse` for lists); errors = RFC 9457 with `code`, `traceId`, `errors[]`.
- Business rules live server-side; the UI renders `allowedActions` from the server.
- Frontend data: access token in memory only (zustand `useSession`), refresh via HttpOnly cookie;
  `lib/api/client.ts` = axios + single-flight refresh + `ApiError` (`messageFor(code)`). Types are
  generated (`make api-types`); response fields nullable only when marked `@Nullable` in Java
  (ADR-023). Server state via TanStack Query (`queryKeys`). Tests: MSW 3 (`onUnhandledFrame`),
  `renderRoutes` from `src/test/render.tsx`. React Bits are vendored in `components/reactbits`
  (local patches marked `// untangle:`).
- Frontend: `src/app`, `src/features/<feature>`, `src/components/{ui,reactbits,three,layout,brand}`,
  `src/lib`, `src/styles/{tokens,globals}.css`. Alias `@/` → `src/`. No `any`. No `dangerouslySetInnerHTML`.

## Conventions
- Java: google-java-format (Spotless), 2-space indent. Tests: JUnit 6 + AssertJ + `MockMvcTester`;
  Testcontainers Postgres for integration (never H2).
- TS: Prettier (single quotes, width 100), named exports, Testing Library queries by role.
- Secrets only via env (`.env.example` documents every variable). Never log tokens, secrets,
  signatures or full emails.
