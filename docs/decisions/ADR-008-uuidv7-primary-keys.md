# ADR-008: UUIDv7 primary keys

- **Status:** Accepted (refines MASTER_PROMPT §8, which suggested `gen_random_uuid()`)
- **Date:** 2026-11-18
- **Phase:** 1

## Context

Ticket ids appear in URLs and API responses, so sequential integers would leak volume ("ticket
4,512") and be guessable. The brief suggests UUIDs via `gen_random_uuid()` (v4, fully random).
Random keys spread inserts across the whole B-tree, which causes page splits and poor cache
locality as tables grow. PostgreSQL 18 ships a native `uuidv7()`, and Hibernate 7 supports
`@UuidGenerator(style = VERSION_7)`.

## Options considered

1. **bigint identity.** Smallest and fastest, but guessable and leaks counts; ids only exist after insert.
2. **UUIDv4** (`gen_random_uuid()`). Unguessable, but random index writes.
3. **UUIDv7.** Unguessable random suffix, time-ordered prefix (milliseconds), 128 bits like v4.

## Decision

UUIDv7. Hibernate generates the id in the application (`@UuidGenerator(VERSION_7)` on
`BaseEntity.id`), so the id is known before the INSERT. The database default `uuidv7()` covers SQL-only
inserts. Demo seed rows use deterministic `md5(key)::uuid` ids, so the seed stays idempotent.

## Consequences

- **Pros:** sequential index inserts like bigint, unguessable like v4, sortable by creation time.
- **Cons:** the id reveals its creation timestamp (to the millisecond). That's acceptable here: ticket
  creation times are shown in the UI anyway. 16 bytes per key rather than 8.
- **Test:** `RepositoryMappingIntegrationTest.savesAgentWithVersionedAuditFieldsAndTimeOrderedId`
  asserts `id.version() == 7`.

## Simple explanation

Every row gets a long random-looking id that can't be guessed, but whose first part is a timestamp,
so the database can file new rows neatly at the end instead of all over the place.
