# How it works — Phase 3: Ticket core

## Simple explanation

Phase 3 is the heart of Untangle: tickets you can actually work.

- **Create** a ticket with customer, description, category and priority. It gets a number like
  `UNT-000061` and a deadline (SLA) from its priority: 1 hour for CRITICAL, up to 72 hours for LOW.
- **Assign** it. Agents can take unassigned tickets for themselves; leads can give any ticket to
  anyone.
- **Move** it through its life: start, resolve (with a note saying how), close, reopen. Each move is
  checked against the rules, and every change is written to the ticket's timeline.
- **Find** tickets by status, priority, agent, category, "mine", "breached", "escalated" or free
  text, page by page, sorted by severity or date.
- **The dashboard** counts unresolved tickets and breaks them down. Every number matches what you'd
  see if you clicked through to the filtered list.
- **No accidental overwrites:** if two people edit the same ticket, the second gets "someone changed
  this, refresh" instead of silently erasing the first change.
- **The screen knows the rules without copying them:** for each ticket the server sends the list of
  buttons you may press, and why the others are disabled.

## Technical details

### 1. Anatomy of a mutation (`POST /tickets/{id}/transitions`)

```
TicketController ── @Valid TransitionTicket (trimmed in compact ctor, then validated)
 └─ TicketService.transition
     1. load (EntityGraph: assignee, creator, partner)            → 404 TICKET_NOT_FOUND
     2. ticket.ensureNotClosed()                                  → 409 TICKET_CLOSED
     3. TicketStateMachine.actionFor(from, to)                    → 409 INVALID_STATUS_TRANSITION
     4. checkVersion(ticket, request.version)                     → 409 VERSION_CONFLICT (+currentVersion)
     5. TicketAccessPolicy.require(action, ticket, me)            → 403 / 409 (reason code)
     6. note / reason present?                                    → 400 RESOLUTION_NOTE_REQUIRED / VALIDATION_FAILED
     7. ticket.transitionTo(to, now)        entity keeps timestamps consistent (DB CHECKs mirror it)
     8. Timeline: RESOLUTION_NOTE (if resolving) + SYSTEM {event, from, to, reason}
     9. publish TicketTransitioned + TicketChanged   (listeners run after commit: Phase 5)
    10. return TicketDetail with fresh allowedActions
 commit → @Version UPDATE ... WHERE version = ?   (catches truly simultaneous writers → 409)
```

### 2. One rulebook for the buttons and the server (ADR-015)

`TicketAccessPolicy.evaluate(action, ticket, me)` combines the closed rule, permissions, ownership
(`ticket.isAssignedTo(me)`), the state machine and preconditions. `require()` enforces it;
`evaluateAll()` builds `allowedActions` + `deniedActions[{action, reason}]` for the detail view.
37 parameterised unit tests pin down the table.

### 3. SLA clock

`sla_due_at = sla_started_at + target(priority)`. Creation sets the start. A priority edit keeps the
start and recomputes the deadline. A reopen restarts the clock and resets `escalation_level` (a new
SLA period begins). Targets live in `app.tickets.sla.*` and an invalid config fails at startup
(`SlaPolicy`).

### 4. Queries

- `TicketSpecifications` holds composable Criteria predicates: no string-built SQL, and user search
  input is LIKE-escaped (`100%` is literal).
- The list query is one SELECT with the assignee fetch-joined (`@EntityGraph`), plus one COUNT. No
  N+1.
- Sorting accepts an allow-listed key. `priority` maps to the generated `priority_rank` column, so
  it sorts by severity. An `id` tie-breaker keeps pages stable.
- `size > 100` → 400 (rejected, not clamped).
- Dashboard counts reuse the same specifications. "SLA breached" is defined once
  (`slaBreached(now)`) and used by the list filter, the dashboard and (Phase 5) the scheduler.

### 5. Mapping and API docs

- MapStruct generates `TicketMapperImpl` at compile time (see `target/generated-sources`).
  `unmappedTargetPolicy=ERROR` means a new view field without a mapping breaks the build. "Now"
  comes in as `@Context`, so SLA fields are computed with the injectable `Clock`.
- springdoc publishes OpenAPI 3 at `/v3/api-docs` and Swagger UI at `/swagger-ui` (bearer auth
  scheme included). Phase 7 generates the frontend's TypeScript types from it.

### 6. Performance check (reproducible)

```sql
-- in a scratch DB with V1+V2 applied:
INSERT INTO agents(...) ...;                    -- 50 agents
INSERT INTO tickets(...) SELECT ... FROM generate_series(1,200000) g ...;
ANALYZE;
EXPLAIN (ANALYZE) SELECT id FROM tickets WHERE status IN ('OPEN','IN_PROGRESS')
  ORDER BY created_at DESC, id DESC LIMIT 20;   -- 0.10 ms, ix_tickets_created_at
```

Full table of plans: [database.md → Query plans](../database.md#query-plans-section-15-verified-with-explain-analyze).

### 7. Verified at the end of Phase 3

| Check | Result |
|---|---|
| `./mvnw verify` | **320 tests** pass (+143 this phase) |
| `make up` + API against demo data | summary `unresolved=25, slaBreached=12, unassigned=8`; list sorted by severity; Swagger 200 with 19 paths |
| `EXPLAIN ANALYZE` at 200k tickets | every list/filter/search query ≤ 1.2 ms, using the intended index |

### Bugs caught by the tests while building

- `Map.copyOf` rejects null values; the assignment timeline entry stores `"from": null` on first
  assignment. Fixed with an unmodifiable `LinkedHashMap` copy, which also preserves key order.
- An ArchUnit layer pattern (`..api..`) matched the shared `common.api` package. Fixed by excluding
  `common` from the feature-layer rules rather than renaming the package.

### Questions your lead might ask

- **"Why check the version before permissions?"** A client with a stale page should be told "refresh"
  (409), not "forbidden" (403). For example, the loser of two simultaneous self-assigns: by the time
  its request is checked, the ticket is already taken.
- **"Why reject page size > 100 instead of clamping?"** A silent clamp returns fewer rows than asked
  for, and a client could wrongly conclude it reached the end.
- **"Why do dashboard counts run several queries?"** Each uses the exact list predicate, so
  consistency is guaranteed by construction. At volume they're index-only and cheap; Phase 10 adds a
  30 s cache.
