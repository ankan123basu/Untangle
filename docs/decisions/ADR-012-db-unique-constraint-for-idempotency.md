# ADR-012: A database unique constraint guarantees webhook idempotency

- **Status:** Accepted
- **Date:** 2026-11-18
- **Phase:** 1 (constraint), Phase 4 (webhook handling on top)

## Context

Partners retry webhooks on timeouts and non-2xx responses, sometimes many times in parallel.
Requirement 8: a duplicate external event id must **never** create a duplicate ticket.

## Options considered

1. **Check, then insert** (`if !exists(event) insert`). Broken under concurrency: 20 parallel
   requests all see "not exists" and all insert.
2. **Application lock or distributed lock** (e.g. Redis) per event id. Works, but adds an
   infrastructure dependency and fails open if the lock service is down.
3. **Unique index** `(partner_id, external_event_id)`. Insert optimistically; the database rejects
   duplicates atomically. On `DataIntegrityViolationException`, load and return the existing ticket.

## Decision

Option 3, with a **partial** unique index `ux_tickets_partner_event ... WHERE external_event_id IS
NOT NULL`, scoped per partner (A9). Phase 4 builds the `201` (new) / `200 duplicate:true` responses
on top of it (A8).

## Consequences

- **Pros:** correct under any concurrency and any number of backend instances; no extra infrastructure.
- **Cons:** the losing insert wastes a little work and a sequence value (so ticket numbers can have
  gaps). The duplicate path is a second query.
- **Test:** `ConcurrencyIntegrationTest.twentyConcurrentInsertsOfOneEventCreateExactlyOneTicket` fires 20
  simultaneous inserts on virtual threads and asserts exactly one row;
  `SchemaConstraintsIntegrationTest.allowsSameEventIdFromDifferentPartners` checks the per-partner scope.

## Simple explanation

The database itself refuses to store two tickets with the same partner and event id, however many
times or however fast the partner retries. The app then simply returns the ticket that already exists.
