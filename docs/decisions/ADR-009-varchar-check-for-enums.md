# ADR-009: VARCHAR + CHECK constraints instead of PostgreSQL ENUM types

- **Status:** Accepted
- **Date:** 2026-11-18
- **Phase:** 1

## Context

Priority, status, category, role, source and several log types are enumerations. Requirement 8
says every ticket must have a **valid** priority, so the database must reject anything else.

## Options considered

1. **Plain varchar, validated only in Java.** Simple, but bad data can enter through SQL, scripts or bugs.
2. **PostgreSQL `ENUM` types.** Compact and strict. But adding a value needs `ALTER TYPE ... ADD VALUE`,
   which historically couldn't run inside a transaction block alongside other DDL. Removing or
   renaming a value is awkward. JDBC/Hibernate need extra type mapping.
3. **`varchar` + named `CHECK (col IN (...))`**, with Java `@Enumerated(EnumType.STRING)`.

## Decision

Option 3. Values are stored as their exact enum names (case-sensitive), so `'high'` and `'URGENT'`
are rejected by `ck_tickets_priority`. Changing the allowed set is a normal Flyway migration that
drops and re-adds the constraint.

## Consequences

- **Pros:** strict, readable in SQL, trivial to evolve, no custom Hibernate types, named
  constraints give precise error messages.
- **Cons:** a few bytes more per row than an ENUM. The list exists in two places (Java enum and SQL
  CHECK); `ddl-auto=validate` doesn't compare them, but the constraint tests do
  (`SchemaConstraintsIntegrationTest.acceptsEveryValidPriority` / `rejectsInvalidPriority`).

## Simple explanation

We store values like `HIGH` as plain text, and the database keeps a strict list of allowed words.
Anything else, even `high` in lowercase, is refused.
