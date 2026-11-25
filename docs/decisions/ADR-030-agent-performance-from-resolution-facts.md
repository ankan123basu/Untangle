# ADR-030: Agent performance stats from one fact row per resolution

- **Status:** Accepted
- **Date:** 2026-11-19
- **Phase:** post-12 (feature requested by the owner)

## Simple explanation

Leads and admins get a **Performance** screen. It shows how fast and how well each agent resolves
tickets, so they can see who is doing well and who needs help:

| Number | What it means |
|---|---|
| **Resolved** | Tickets the agent resolved in the period (7, 30 or 90 days) |
| **Avg / median handle time** | From the moment the agent got the ticket to the moment they resolved it |
| **Median customer wait** | From the moment the customer asked to the moment it was resolved |
| **SLA met** | Share of their resolutions that happened before the SLA deadline |
| **Reopened** | Share of their resolutions that didn't stick: the ticket was reopened or sent back to work |
| **Open now** | Unresolved tickets they hold right now |

Every time a ticket is resolved, the app writes down **one fact row**: who owned the ticket, when
they got it, when the customer asked, when it was resolved and what the deadline was. If the ticket
comes back later, that row is marked "reopened". The screen is a `GROUP BY` over these rows.

The credit goes to **whoever owned the ticket** at that moment. When a lead approves Unty's proposal,
Unty gets the credit and the lead is recorded as the person who pressed the button.

"Top performers" (most resolved, fastest, best on SLA, fewest reopens) only count agents with at
least **3** resolutions in the period, so one lucky ticket doesn't make anyone "the fastest".

## Context

The owner asked for statistics on "how much time on average each agent takes to solve a ticket",
visible to admins and leads, to find the high performers.

The `tickets` table alone can't answer this:

- It only remembers the **current** assignee. A ticket resolved by Asha and later reassigned to Dev
  would count for Dev.
- It forgets earlier resolutions when a ticket is reopened (`resolved_at` is cleared).
- There was no record of **when** the current assignee got the ticket, so "handle time" (the agent's
  own speed) couldn't be told apart from the customer's total wait.

| Option | Verdict |
|---|---|
| Compute from `tickets` only | Wrong credit after reassignment; loses reopened resolutions |
| Parse the timeline (`ticket_comments` metadata) | Possible, but JSON parsing in every query; slow and fragile |
| Full assignment history table (every assign/unassign) | Most flexible, but more than this screen needs today |
| **One fact row per resolution** (+ `tickets.assigned_at`) | **Chosen**: exact credit, simple fast SQL, keeps history |

## Decision

1. **Schema (V7):**
   - `tickets.assigned_at`: set by `Ticket.assignTo(agent, now)`, cleared on release, and restarted
     on reopen (a new work period). It is backfilled from the latest `ASSIGNED` timeline event.
   - `ticket_resolutions`: `ticket_id`, `agent_id` (credited assignee), `resolved_by_agent_id`,
     `priority`, `category`, `opened_at`, `assigned_at`, `resolved_at`, `sla_due_at`,
     `reopened_at`.
   - The constraint `ck_resolutions_order` (opened ≤ assigned ≤ resolved).
   - `ux_ticket_resolutions_standing`: at most one standing (not reopened) resolution per ticket.
   - The migration backfills one row per already RESOLVED or CLOSED ticket.
2. **Writing** (in `TicketService`, the same transaction as the status change):
   - RESOLVE: `TicketResolution.of(ticket, actor)` is saved.
   - RESOLVED → IN_PROGRESS, and CLOSED → OPEN: `markReopened` sets `reopened_at` on the standing
     row.
3. **Reading** (`AgentPerformanceStats`, `GET /api/v1/dashboard/agent-performance?days=1..365`):
   - Two native queries: per agent (CTE + `LEFT JOIN agents`) and a team row. Medians use
     PostgreSQL `percentile_cont(0.5)`; per-agent medians can't be combined into a team median, so
     the team row is its own query.
   - All active AGENTs (people and Unty) are listed even with nothing resolved. Leads and
     deactivated agents appear only if they resolved something in the period.
   - A metric is `null` (not 0) when there is no data.
   - `ranked` = at least `MIN_RESOLVED_TO_RANK` (3) resolutions.
4. **Who:** `AGENT_WORKLOAD_VIEW` (leads and admins), like the workload widget. Agents don't see
   colleagues' numbers.
5. **Screen:** `/performance`. It has team tiles, top-performer stickers, and a sortable table
   (agents without data always last). It also explains how the numbers are calculated.

## Consequences

- **Pros:**
  - Credit is exact even after reassignment and reopening.
  - History is kept.
  - Queries hit one small indexed table (`ix_ticket_resolutions_resolved_agent`).
  - The same facts could later feed auto-assign weighting (ADR-029 "not done").
- **Cons:**
  - Times don't adjust for difficulty: a CRITICAL outage and a LOW question count the same. Leads
    should read the numbers together with the priority mix.
  - Handle time is wall-clock time, including nights and weekends (there are no working hours yet).
  - The backfill assumes the current assignee resolved older tickets, which is the best we know for
    data written before V7.
- **Not done:**
  - A per-priority breakdown.
  - Trends over time per agent.
  - A first-response metric per agent.
  - CSV export.
- **Tests:**
  - `AgentPerformanceIntegrationTest` (7):
    - credit to the assignee, including when a lead resolves
    - reopen and return-to-progress are marked and a new resolution is recorded
    - median, average, SLA and the period window
    - leads only appear once they resolve
    - RBAC and validation
    - the DB rejects an impossible timeline
  - Frontend: `performance.test.tsx` (4).
