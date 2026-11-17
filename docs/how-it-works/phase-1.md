# How it works — Phase 1: Database

## Simple explanation

Phase 1 gives Untangle its **memory**. When the backend starts, it:

1. connects to PostgreSQL,
2. runs any new **migration scripts** (numbered SQL files) to create or update tables,
3. checks that the Java code's idea of each table matches the real table, and refuses to start if
   not,
4. with the `demo` profile, fills the database with a realistic team and 60 tickets.

The most important business rules now live **in the database itself**:

- A ticket's priority must be exactly `LOW`, `MEDIUM`, `HIGH` or `CRITICAL`.
- A partner can never create two tickets with the same event id, even with 20 retries at the same instant.
- A closed ticket must have a closing time, and a ticket "in progress" must have someone assigned.
- A comment can never be edited after it's written; the timeline is an audit log.

So even if a future bug in Java tried to save something invalid, the database would say no.

## Technical details

### 1. Startup sequence

```
Spring Boot starts
 └─ DataSource (HikariCP) → jdbc:postgresql://… (localhost:5433 locally, postgres:5432 in Docker)
 └─ Flyway
     ├─ db/migration/V1__init.sql              (always)
     └─ db/seed-demo/R__demo_data.sql          (only with profile `demo`; `dev` includes it)
 └─ Hibernate EntityManagerFactory
     └─ ddl-auto=validate: compares every @Entity with the live schema → fail fast on mismatch
 └─ App ready → readiness probe UP
```

Real example: during Phase 1, validation caught `token_hash` declared as `char(64)` in SQL but
mapped as `varchar` in Java, before any test ran. We switched the column to `varchar(64)` plus a
regex CHECK. This is ADR-003 working as intended.

### 2. Schema highlights (`V1__init.sql`)

| Feature | How | Why |
|---|---|---|
| UUIDv7 ids | `DEFAULT uuidv7()` + Hibernate `@UuidGenerator(VERSION_7)` | unguessable and time-ordered (ADR-008) |
| `UNT-000123` numbers | sequence + `next_ticket_number()` default; `@Generated` reads it back | concurrency-safe, no `max()+1` race; never truncates past 999 999 |
| Valid enums only | `varchar` + named `CHECK` | strict yet easy to evolve (ADR-009) |
| No duplicate partner events | partial unique index `ux_tickets_partner_event` | correct under any concurrency (ADR-012) |
| Lifecycle invariants | `ck_tickets_closed_has_timestamp`, `…_resolved_…`, `…_in_progress_assigned`, `ck_tickets_source_consistency`, `ck_tickets_creator` | defence in depth for the state machine |
| Append-only timeline | `BEFORE UPDATE` trigger `forbid_update()` + `@Immutable` | trustworthy audit trail (ADR-010) |
| Optimistic locking | `version bigint` + `@Version` | no silent lost updates (ADR-011) |
| Fast queries | 15 indexes, several **partial** | each one justified in database.md |

### 3. Java model

```
common.audit.BaseEntity        id (UUIDv7) + identity-safe equals/hashCode
   └─ AuditedEntity             createdAt, updatedAt, @Version   ← Agent, Partner, Ticket
<feature>.domain                entities + enums (no Spring imports, enforced by ArchUnit)
<feature>.infrastructure        Spring Data repositories (private to the feature, enforced)
```

- **No public setters.** Tickets are created only through `Ticket.manual(...)` or
  `Ticket.fromWebhook(...)`, so a half-built ticket is impossible. Phase 3 adds behaviour methods
  (`assignTo`, `transitionTo`, ...) guarded by the state machine.
- **Lombok `@Getter` only.** Never `@Data` on entities: it would generate `equals/hashCode` over
  mutable fields and lazy relations, which breaks Hibernate.
- **LAZY relations** everywhere, plus `default_batch_fetch_size=32` and an `@EntityGraph` on the
  timeline query to avoid N+1 queries. `open-in-view=false` keeps lazy loading inside services.
- **JSONB** (`metadata`, `content`) maps to `Map<String,Object>` through Hibernate 7's native
  Jackson 3 support (`@JdbcTypeCode(SqlTypes.JSON)`).
- **Repository queries are named for the feature that needs them**, for example
  `findByPartnerIdAndExternalEventId` (webhook duplicate lookup) and `countByRoleAndActiveTrue`
  (last-admin rule).

### 4. Demo data

`R__demo_data.sql` is a repeatable Flyway migration: it re-runs when the file changes, and
`ON CONFLICT DO NOTHING` with deterministic ids makes re-runs harmless. Ticket ages follow a curve
(dense recent, sparse old), so the queue and the 14-day trend look realistic. Every row passes the
V1 constraints; `DemoSeedIntegrationTest` proves it.

### 5. Tests (72, all against real PostgreSQL 18 via Testcontainers)

| What | How it's proven |
|---|---|
| Every DB rule rejects bad data | `SchemaConstraintsIntegrationTest`: raw SQL bypasses Java on purpose (33 tests) |
| Entities round-trip correctly | `RepositoryMappingIntegrationTest`: `flush()` + `clear()` forces a real INSERT and SELECT (13) |
| 20 simultaneous duplicate events → 1 ticket | `ConcurrencyIntegrationTest`: a latch releases 20 virtual threads at once |
| Stale update rejected | `ConcurrencyIntegrationTest`: two copies at version 0; the second save throws |
| Architecture can't erode | `ArchitectureTest`: 15 ArchUnit rules |

### 6. Verified at the end of Phase 1

| Check | Result |
|---|---|
| `make ci` | backend 72 tests ✅, frontend lint/types/tests/build ✅ |
| `make up` | Flyway applied `V1` + demo seed in Docker; Hibernate validated; all containers healthy |
| Demo data in the Docker DB | OPEN 15 · IN_PROGRESS 10 · RESOLVED 13 · CLOSED 22 |

### Questions your lead might ask

- **"Why enforce rules in both Java and SQL?"** Java gives friendly errors (400/409 with field
  details). SQL guarantees correctness even under bugs, scripts and concurrency. The race test shows
  why Java alone isn't enough.
- **"Why not just catch duplicates with `existsBy…` first?"** Twenty parallel requests would all
  see "doesn't exist". Only an atomic unique index is correct; we still use the lookup *after* the
  insert fails, to return the original ticket.
- **"Why is `ticket_ai_insights.ticket_id` nullable when the brief lists it?"** Triage runs on the
  create form, before the ticket exists. A CHECK keeps it non-null for every other kind.
