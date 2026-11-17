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
