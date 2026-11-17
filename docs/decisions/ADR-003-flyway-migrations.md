# ADR-003: Flyway owns the schema; Hibernate only validates

- **Status:** Accepted
- **Date:** 2026-11-18
- **Phase:** 0 (applied from Phase 1)

## Context

The schema relies on things an ORM can't generate well: partial unique indexes
(`(partner_id, external_event_id) WHERE external_event_id IS NOT NULL`), multi-column CHECK
constraints, a sequence for `UNT-000123` numbers, `pg_trgm` GIN indexes. Every environment must
have the same schema, and changes must be reviewable.

## Options considered

1. **`spring.jpa.hibernate.ddl-auto=update`.** Zero effort, but it never drops or renames anything,
   can't express our constraints, and differs silently between environments.
2. **Liquibase.** Powerful, database-agnostic XML/YAML changelogs, but more indirection than we need
   for a PostgreSQL-only project.
3. **Flyway with plain SQL migrations** (`V1__init.sql`, `V2__...`) and `ddl-auto=validate`.

## Decision

Option 3. Migrations live in `backend/src/main/resources/db/migration`. Hibernate runs with
`ddl-auto=validate`, so the app fails to start if entities and schema drift apart. Dev-only demo
data lives in a separate location (`db/seed-dev`) that is added to `spring.flyway.locations` only
in the `dev` profile. Flyway's version is the one managed by Spring Boot 4.1.2 (12.4.x).

## Consequences

- **Pros:** the schema is code-reviewed SQL. Identical in every environment. Full PostgreSQL feature
  set. Startup validation catches mapping mistakes early.
- **Cons:** every change is a new migration file; applied migrations are never edited.
- **Follow-ups:** Phase 1 writes `V1__init.sql` and Testcontainers tests that run it against
  PostgreSQL 18.

## Simple explanation

Database changes are written as numbered SQL scripts that run in order, like a change log for the
database. The app checks at startup that its code matches the database, and refuses to run if not.
