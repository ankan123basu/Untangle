# Untangle documentation

> *Every complaint, untangled.*

Start with **problem-statement.md**, then **architecture.md**. Each document has a
**Simple explanation** (plain words) and **Technical details** section.

| Document | What it answers | Status |
|---|---|---|
| [problem-statement.md](problem-statement.md) | What are we building, for whom, and how do we know it works? | Done |
| [assumptions-and-open-questions.md](assumptions-and-open-questions.md) | What wasn't specified, what we chose, what to ask the lead | Done (living) |
| [tech-stack.md](tech-stack.md) | Exact pinned versions and why | Done (living) |
| [architecture.md](architecture.md) | How the pieces fit together; request flows | Phase 0 outline, grows each phase |
| [database.md](database.md) | Tables, constraints, indexes, ER diagram | Done (Phase 1) |
| [state-machine.md](state-machine.md) | Ticket lifecycle rules and who may do what | Done (spec); code in Phase 3 |
| [api.md](api.md) | Endpoints, error codes, pagination | Phase 3 |
| [security.md](security.md) | Threat model, auth, RBAC, HMAC, secrets | Phases 2 and 4 |
| [webhook-integration-guide.md](webhook-integration-guide.md) | For partners: how to send signed tickets | Phase 4 |
| [tradeoffs.md](tradeoffs.md) | Options, choice, gained, given up | Living |
| [decisions/](decisions/) | Architecture Decision Records (ADRs) | Living |
| [edge-cases.md](edge-cases.md) | Case → expected behaviour → test name | Living (DB-level cases done) |
| [testing.md](testing.md) | Test strategy and how to run tests | Phase 0 baseline |
| [ai-features.md](ai-features.md) | AI helpers, safety, failure modes | Phase 6 |
| [frontend-design-system.md](frontend-design-system.md) | Tokens, theme, motion, a11y | Phases 7 and 9 |
| [scalability.md](scalability.md) | Today's scaling choices and the 10x/100x plan | Phases 10–11 |
| [observability.md](observability.md) | Logs, metrics, health, dashboards | Phases 2–10 |
| [runbook.md](runbook.md) | Run locally, env vars, common failures | Phase 0 baseline |
| [how-it-works/](how-it-works/) | One "How it works" note per phase | One per phase |

## Architecture Decision Records

| ADR | Decision |
|---|---|
| [000](decisions/ADR-000-template.md) | Template |
| [001](decisions/ADR-001-modular-monolith-package-by-feature.md) | Modular monolith, package-by-feature |
| [002](decisions/ADR-002-java-21-spring-boot-4.md) | Java 21 LTS + Spring Boot 4.1 |
| [003](decisions/ADR-003-flyway-migrations.md) | Flyway owns the schema (`ddl-auto=validate`) |
| [004](decisions/ADR-004-local-toolchain-docker-runtime.md) | Local toolchain for dev, Docker for runtime and CI |
| [005](decisions/ADR-005-redis-optional.md) | Redis is optional |
| [006](decisions/ADR-006-frontend-toolchain.md) | Frontend toolchain: Vite 8, TS 6.0, Tailwind v4 |
| [007](decisions/ADR-007-response-and-error-format.md) | Plain DTOs on success, RFC 9457 on error |
| [008](decisions/ADR-008-uuidv7-primary-keys.md) | UUIDv7 primary keys |
| [009](decisions/ADR-009-varchar-check-for-enums.md) | VARCHAR + CHECK instead of PG ENUM |
| [010](decisions/ADR-010-append-only-comments.md) | Comments are an append-only audit trail |
| [011](decisions/ADR-011-optimistic-locking.md) | Optimistic locking with `@Version` |
| [012](decisions/ADR-012-db-unique-constraint-for-idempotency.md) | DB unique constraint guarantees webhook idempotency |
