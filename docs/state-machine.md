# Ticket state machine

## Simple explanation

A ticket moves through four states: **OPEN** (waiting), **IN_PROGRESS** (someone is on it),
**RESOLVED** (fixed, waiting for confirmation) and **CLOSED** (done and frozen). Only certain moves
are allowed, and some moves need extra information. For example, you can't mark a ticket resolved
without writing a resolution note. A closed ticket can't be changed at all until a lead reopens it.

The server decides which moves are allowed and tells the UI (`allowedActions`), so the screen never
offers a button the server would reject.

## Technical details

```mermaid
stateDiagram-v2
  [*] --> OPEN: create (manual or webhook)
  OPEN --> IN_PROGRESS: start (must be assigned)
  OPEN --> CLOSED: close as spam/duplicate/invalid (reason)
  IN_PROGRESS --> OPEN: release to queue (clears assignee)
  IN_PROGRESS --> RESOLVED: resolve (resolution note)
  RESOLVED --> IN_PROGRESS: fix didn't work (reason)
  RESOLVED --> CLOSED: close
  CLOSED --> OPEN: reopen via /reopen (reason)
```

| From | To | Who | Conditions / side effects |
|---|---|---|---|
| OPEN | IN_PROGRESS | assignee, LEAD, ADMIN | must be assigned (`ASSIGNEE_REQUIRED`); sets `first_response_at` if null |
| OPEN | CLOSED | LEAD, ADMIN | `reason` required; sets `closed_at` |
| IN_PROGRESS | OPEN | assignee, LEAD, ADMIN | clears assignee |
| IN_PROGRESS | RESOLVED | assignee, LEAD, ADMIN | resolution note required (`RESOLUTION_NOTE_REQUIRED`); sets `resolved_at` |
| RESOLVED | IN_PROGRESS | assignee, LEAD, ADMIN | `reason` required |
| RESOLVED | CLOSED | LEAD, ADMIN (assignee if `app.tickets.agent-can-close-own=true`) | sets `closed_at` |
| CLOSED | OPEN | LEAD, ADMIN, only via `POST /tickets/{id}/reopen` | `reason` required; `reopened_count++`; keeps assignee unless inactive (R5); SLA recalculated (R4) |

**Rules**

- Any other move, including same-status → `409 INVALID_STATUS_TRANSITION`.
- Every successful move appends a `SYSTEM` timeline entry `{from, to, actor, reason}`.
- While `CLOSED`, every mutation except reopen → `409 TICKET_CLOSED` (one guard in the service layer).
- `GET /tickets/{id}` returns `allowedActions`, computed from this table **and** the caller's role.

### Implementation (Phase 3)

| Piece | Responsibility | Tests |
|---|---|---|
| `TicketStateMachine` (domain) | the table above; which moves need a reason or a note | `TicketStateMachineTest`: all 16 from→to pairs |
| `Ticket` behaviour methods | closed guard; lifecycle timestamps (`first_response_at`, `resolved_at`, `closed_at`); release clears the assignee; reopen restarts the SLA and resets escalation | via HTTP tests |
| `TicketAccessPolicy` (security) | who may perform each action now; produces `allowedActions` / `deniedActions` | `TicketAccessPolicyTest`: 37 role × ownership × status cases |
| `TicketService` | order: load → closed → version → policy → input checks → mutate → timeline → event | `TicketLifecycleIntegrationTest` (42) |

### SLA and escalation (Phase 5)

- `sla_due_at = sla_started_at + target(priority)`; targets in `app.tickets.sla.*`.
- Every minute, one instance (ShedLock) raises `escalation_level` to
  `min(3, floor((now − sla_started_at) / target))` for OPEN/IN_PROGRESS tickets past their deadline,
  writes a SYSTEM entry `{event: SLA_BREACH, level}` and notifies leads + assignee
  ([ADR-020](decisions/ADR-020-sla-scheduler-with-shedlock.md)).
- Manual `escalate` adds 1 (any role, reason required). Reopen restarts the SLA clock and resets the level.

Reason codes: `TICKET_CLOSED`, `ACCESS_DENIED`, `INVALID_STATUS_TRANSITION`, `ASSIGNEE_REQUIRED`.
The UI hides `ACCESS_DENIED` and `INVALID_STATUS_TRANSITION` actions and shows a "View only" note;
only `ASSIGNEE_REQUIRED` is shown disabled with its reason
([ADR-015](decisions/ADR-015-allowed-actions-from-server.md)).
