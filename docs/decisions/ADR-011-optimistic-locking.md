# ADR-011: Optimistic locking with `@Version`

- **Status:** Accepted
- **Date:** 2026-11-18
- **Phase:** 1 (API behaviour in Phase 3)

## Context

Several people can work the same ticket at once: an agent editing priority while a lead reassigns
it. Without protection, the last save silently overwrites the first ("lost update").

## Options considered

1. **Last write wins.** Simple, but silently loses work.
2. **Pessimistic locking** (`SELECT ... FOR UPDATE`, held while a user edits). Safe, but locks would
   be held across HTTP requests, and blocked users wait or deadlock.
3. **Optimistic locking.** Each row has a `version`. Updates say `WHERE version = :seen`, and a
   mismatch fails fast.

## Decision

Option 3. `AuditedEntity` (agents, partners, tickets) and `RefreshToken`/`TicketAiInsight` carry
`@Version`. Mutating ticket requests send the `version` the client last saw (R7); a mismatch maps to
`409 VERSION_CONFLICT` and the UI offers a refresh. Comments and escalations don't need a version:
they append or increment atomically.

## Consequences

- **Pros:** no locks held between requests; conflicts are rare and visible; scales across instances.
- **Cons:** clients must carry `version`; a conflict means the user retries.
- **Test:** `ConcurrencyIntegrationTest.staleUpdateIsRejectedByOptimisticLocking` (two copies at
  version 0: the first save wins, the second throws `ObjectOptimisticLockingFailureException`).

## Simple explanation

Every ticket has a version number. If you try to save changes to version 3 but someone already
saved version 4, the system stops you and asks you to refresh, instead of quietly erasing their change.
