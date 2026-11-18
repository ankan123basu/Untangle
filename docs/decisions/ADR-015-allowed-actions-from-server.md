# ADR-015: The server computes `allowedActions`, using the same code that enforces them

- **Status:** Accepted
- **Date:** 2026-11-18
- **Phase:** 3

## Context

Whether a button should appear on the ticket page depends on the role, on ownership (am I the
assignee?), on the status (state machine) and on the closed rule. If the frontend re-implemented
these rules, the two copies would drift: the UI would offer actions the server rejects, or hide
actions people are allowed to do.

## Options considered

1. **Duplicate the rules in TypeScript.** Fast UI, but two sources of truth, and drift is guaranteed.
2. **Optimistic UI.** Show every button and let the server say no. Honest, but a poor experience
   ("why can I click Close if I'm not allowed to?").
3. **Server-computed actions.** `GET /tickets/{id}` returns `allowedActions` (and `deniedActions`
   with a reason code), produced by `TicketAccessPolicy.evaluateAll`, the same evaluator that
   `TicketService` calls via `require()` before every mutation.

## Decision

Option 3. `TicketAccessPolicy.evaluate(action, ticket, me)` is the single implementation. Checks
run in a fixed order, so the reason given is the most useful one:

1. closed → `TICKET_CLOSED`
2. permission and ownership → `ACCESS_DENIED`
3. state machine → `INVALID_STATUS_TRANSITION`
4. preconditions → `ASSIGNEE_REQUIRED`

The UI renders enabled buttons from `allowedActions`. Actions denied with `ACCESS_DENIED` (the
person's role or ownership) or `INVALID_STATUS_TRANSITION` are **hidden**, and a ticket the person
can't edit shows a "View only" note naming the assignee. Only actions that need a step first
(`ASSIGNEE_REQUIRED`) stay visible, disabled, with a tooltip from the reason code.

*Update (manual testing):* faded-but-visible Edit/Resolve buttons on another agent's ticket looked
usable to a tester, although the server refused them (403). Hiding them removes that confusion.

## Consequences

- **Pros:** zero drift between UI and enforcement, guaranteed by construction; the rules are
  unit-tested in one place (`TicketAccessPolicyTest`, 37 cases); the frontend stays simple.
- **Cons:** the detail payload is a little larger (11 action entries). After every mutation the UI
  uses the returned detail (which includes fresh actions), so there's no extra round-trip.
- **Note:** mutations check the closed rule and the client's `version` *before* the policy. So a
  client working from a stale page gets `409 VERSION_CONFLICT` ("someone changed this, refresh")
  rather than a confusing 403. For example, the loser of two simultaneous self-assigns.

## Simple explanation

The server tells the screen exactly which buttons to show, using the same rulebook it uses to say
yes or no. So the screen can never offer something the server will refuse.
