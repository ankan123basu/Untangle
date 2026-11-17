# ADR-020: SLA escalation as an idempotent scheduled scan, guarded by ShedLock and row locks

- **Status:** Accepted
- **Date:** 2026-11-18
- **Phase:** 5

## Context

Tickets past their SLA must be escalated automatically (§5.4), exactly once per level, even with
several backend instances, restarts or a slow run overlapping the next one.

## Options considered

1. **Plain `@Scheduled`.** Every instance runs it, so N instances escalate N times.
2. **Message queue with delayed messages per ticket.** Precise timing, but a new piece of
   infrastructure, and every priority edit or reopen would need to reschedule its message.
3. **One periodic scan (every minute) + ShedLock + idempotent per-ticket logic + `FOR UPDATE` row
   locks.**

## Decision

Option 3.
- **ShedLock** (`shedlock` table, `usingDbTime()`) lets one instance run `sla-escalation` per
  minute. `lockAtMostFor = 5m` frees the lock if the instance dies; `lockAtLeastFor = 20s` stops a
  near-simultaneous double fire.
- **Idempotency** doesn't depend on ShedLock. The due level is computed from time
  (`floor((now − sla_started_at) / target)`, capped at `max-level` = 3), and
  `Ticket.escalateToAtLeast(level)` only ever raises. Re-runs and concurrent runs are no-ops for
  tickets already at that level.
- Candidates are selected with `SELECT ... FOR UPDATE`, using the partial index
  `ix_tickets_sla_due_unresolved`, so a concurrent run blocks and then sees the raised level.
- Each escalation writes a SYSTEM timeline entry and publishes `TicketEscalated(automatic=true)`;
  notifications follow after commit.
- The same mechanism runs the daily 30-day purge of `webhook_deliveries`.

## Consequences

- **Pros:** no new infrastructure; safe with N instances; easy to reason about and test (the job is
  a plain method taking `now`).
- **Cons:** up to about 1 minute of latency after a breach; a full scan of breached rows each minute
  (index-backed: 0.08 ms for 100 rows at 200k tickets, see database.md).
- **Tests:** `SlaEscalationIntegrationTest`: levels and cap, re-run idempotency, **two concurrent
  runs → each ticket escalated once**, manual level above due is kept, notifications, ShedLock
  exclusivity.

## Simple explanation

Once a minute, one server checks for tickets that are too late and raises their alarm level. The
check can safely run twice: a ticket that already has the right alarm level is left alone.
