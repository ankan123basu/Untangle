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
- Coverage gate: JaCoCo ≥ 80% on `service`/`domain` packages, enforced from Phase 10.

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
