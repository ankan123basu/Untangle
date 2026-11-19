# ADR-029: Automatic assignment to the least busy agent who handles the ticket

- **Status:** Accepted
- **Date:** 2026-11-19
- **Phase:** post-12 (feature requested by the owner)

## Simple explanation

Assigning tickets one at a time doesn't work at 10,000+ tickets, so leads can switch on
**auto-assign**. Each waiting ticket then goes to the **least busy agent who handles that kind of
ticket**, most urgent first:

1. **Most urgent first.** The queue is taken in order of SLA deadline. A CRITICAL ticket, due in
   1 hour, comes before a LOW one due in 3 days.
2. **Least busy wins.** An agent's workload counts each unresolved ticket by priority:
   CRITICAL 4, HIGH 3, MEDIUM 2, LOW 1. The lowest workload gets the ticket. On a tie, whoever has
   waited longest since their last automatic ticket goes first (round-robin).
3. **They must fit.** The agent must:
   - handle the category (BILLING, TECHNICAL, …; an agent with no category picked handles all of
     them)
   - be under the cap (default 10 open tickets)
   - not be the person who just gave that ticket back
4. **Unty only gets routine work.** The AI teammate only gets LOW and MEDIUM tickets, and only if
   a lead switches it on.
5. **If nobody fits, the ticket waits.** It stays in the queue, SLA escalation still watches it,
   and the next sweep tries again. Nobody is overloaded to make room.

New tickets, from the app or a partner webhook, are assigned the moment they are created. A
background sweep every 30 seconds catches everything else: released tickets, tickets the AI handed
back, and tickets that waited because everyone was at the cap. Leads can also press **Assign
waiting now**.

## Context

The owner asked whether an algorithm such as topological sort could assign tickets
automatically. Topological sort orders tasks that depend on each other. Tickets don't depend on
each other, so this is a **load-balancing** problem instead.

| Option | Verdict |
|---|---|
| Round-robin | Simple, but ignores how busy people already are and what they know |
| Random | Same problem, and harder to explain |
| **Weighted least-loaded with skills and a cap** | Chosen: fair, explainable, fast enough at 10K+ tickets |
| Optimal matching (Hungarian algorithm, O(n³)) | Overkill: slower, hard to explain, and the result is nearly the same for this problem |
| Pull ("next ticket" button) | Complements this; could be added later |

## Decision

1. **Planner** (`assignment.domain.AssignmentPlanner`): pure Java with no Spring, so it is unit
   tested.
   - It keeps one ordered set (a balanced tree used as a min-heap) per category, holding the
     agents who handle that category.
   - The order is (weighted load, open count, last automatic pick, id).
   - A pick takes the first eligible agent. That agent is removed from their sets, their load is
     updated, and they are re-inserted, or dropped once they reach the cap.
   - Cost: O(C·log A) per ticket, for C categories and A agents. 10,000 tickets × 50 agents run
     in well under a second (`tenThousandTicketsAcrossFiftyAgentsIsFastAndFair`).
2. **Queue and locking** (`ticket.application.TicketQueue`): the queue is OPEN, unassigned tickets
   ordered by `(sla_due_at, id)`.
   - Reads use keyset paging (`ix_tickets_unassigned_queue`), and the rows are locked with
     `FOR UPDATE SKIP LOCKED`. Two runs, or a run and a new-ticket trigger, never take the same
     ticket, and neither waits for the other.
   - These calls require the caller's transaction (`MANDATORY`), so the lock is held while the
     ticket is assigned.
3. **Acting:** every assignment goes through `TicketService.autoAssign`.
   - It re-checks that the ticket is still OPEN and unassigned, and that the agent is active.
   - It records the usual `ASSIGNED` timeline line with `automatic: true` and the reason ("least
     busy BILLING specialist, workload 3").
   - It sends the usual notification and `TicketAssigned` event (`actorId` null), so Unty's
     hand-back goes to the queue.
4. **Triggers:**
   - **New ticket:** `@TransactionalEventListener` on `TicketCreated`. It runs in its own
     transaction, and a failure is logged and never fails the request.
   - **Sweep:** `@Scheduled` every 30 s with ShedLock (`auto-assign`); it only runs while the
     switch is on.
   - **Run now:** `POST /auto-assign/run` works even while the switch is off. It is a lead's
     explicit action.
5. **Settings:**
   - `auto_assign_settings` holds the switch and the cap in a single row (a unique index on a
     constant). No row means switched off, with a cap of 10.
   - `agent_routing` holds whether each agent takes automatic work, plus their categories
     (`agent_routing_skills`). An agent without a row uses the defaults: human AGENTs take work;
     leads, admins and Unty don't.
   - `last_auto_assigned_at` is written with a plain upsert, with no `@Version` bump, so a
     running sweep never conflicts with a lead editing routing.
6. **No straight hand-back:** `tickets.released_by_agent_id` is set when the assignee releases the
   ticket or the AI hands it back (`Ticket.giveBack`). The planner skips that agent for that
   ticket.
7. **Who:** leads and admins (`TICKET_ASSIGN_ANY`) for the screen and every endpoint. Agents
   simply receive work.

## Consequences

- **Pros:**
  - No manual triage at scale.
  - Work is spread fairly, by effort rather than ticket count.
  - Specialists get their categories.
  - Every assignment explains itself on the timeline.
  - It is safe across several instances.
- **Cons:**
  - The cap is a soft limit while a lead assigns by hand during a run: it can be over by at most
    that run's picks.
  - The load counts priority, not real effort. A long LOW ticket still counts as 1.
- **Deviation from the first sketch:** the admin switch, cap and per-agent routing are runtime
  settings on a new **Auto-assign** screen (leads and admins), not environment variables. There is
  no per-agent maximum, only one team-wide cap.
- **Not done (possible next steps):**
  - Working hours and away status.
  - Per-agent caps.
  - Weighting by measured handling time (this needs the assignment history planned for agent
    performance stats).
  - Pull mode.
- **Tests:**
  - `AssignmentPlannerTest` (10 tests): weights, round-robin, skills, cap, no straight hand-back,
    AI only LOW and MEDIUM, tie-break, explanation, 10K × 50 speed and fairness.
  - `AutoAssignIntegrationTest` (9 tests): off by default, a new ticket goes to the least busy
    agent with a timeline line and a notification, the sweep goes most urgent first and stops at
    the cap, leads/admins/AI are excluded by default, categories route, a releaser isn't handed the
    ticket back, deactivated agents are excluded, 3 concurrent runs assign each ticket exactly
    once, RBAC and validation.
  - Frontend: `auto-assign.test.tsx` (3 tests).
