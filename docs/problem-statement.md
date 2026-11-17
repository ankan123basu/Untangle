# Problem statement

## Simple explanation

Support teams get complaints from two places: **customers** (an agent types them in) and
**partner companies** whose software forwards complaints automatically. Without a proper system:

- complaints get **lost** (they live in inboxes and chats),
- nobody clearly **owns** a complaint,
- **urgent** problems wait behind trivial ones,
- managers **can't see** how big the backlog is,
- partner systems **retry** messages, so the same complaint shows up twice.

**Untangle** turns every complaint into a **ticket** that is prioritised, assigned to one person,
moved through a clear lifecycle (`OPEN → IN_PROGRESS → RESOLVED → CLOSED`), commented on,
**escalated automatically** if it misses its deadline (SLA), and **never duplicated**. Partners send
tickets through a **signed webhook**, so we can prove a request really came from them.

## Who uses it, and why

| User | What they need | Untangle features for them |
|---|---|---|
| **Support agent** | Know what to work on next, work it to completion, never lose context | "My open" queue, ticket detail and timeline, resolution notes, AI reply drafts, notifications |
| **Team lead** | See the backlog and what's at risk, balance work, step in on escalations | Dashboard (unresolved count, SLA breaches, workload), assign/reassign, close/reopen, AI thread summary |
| **Admin** | Manage the team and partner integrations safely | Agent management, partner secrets and rotation, webhook delivery logs |
| **Partner system** | Hand over complaints reliably, even with retries | HMAC-signed webhook, idempotent by event id, clear response codes |

## Original requirements (all must be met end to end)

1. Create tickets with customer name, issue description, category and priority.
2. Assign tickets to support agents.
3. Statuses `OPEN`, `IN_PROGRESS`, `RESOLVED`, `CLOSED`.
4. Comments or resolution notes on tickets.
5. Filter by priority, assigned agent and status.
6. Dashboard shows the number of unresolved tickets.
7. Partner tickets arrive via a signed webhook; the HMAC is validated before anything is accepted.
8. Closed tickets can't be modified without reopening; every ticket has a valid priority; duplicate
   external event IDs never create duplicate tickets.
9. React + Spring Boot + PostgreSQL (tables at least `tickets`, `agents`, `ticket_comments`).
10. Screens: dashboard, ticket detail, create/assign form.

## Success criteria

- Every requirement above is usable in the browser and covered by automated tests.
- `make simulate` shows every webhook scenario (valid, duplicate, 20 concurrent duplicates,
  tampered, wrong secret, expired, rotation...) behaving as expected.
- A reviewer can clone, run `make up`, and demo the product in 5 minutes.
- Lighthouse performance and accessibility ≥ 90 on the dashboard.
- Service/domain test coverage ≥ 80%; CI green.

## Technical details

- Two entry points share one business layer: JWT-authenticated REST for people, HMAC-verified
  webhooks for partners. Both call the same `TicketService`, so the rules are identical.
- Rules are enforced on the server (state machine, closed-ticket guard, DB unique constraint for
  idempotency). The UI only reflects what the server allows (`allowedActions`).
- Full specification: [`MASTER_PROMPT.md`](../MASTER_PROMPT.md). Phase plan: Section 14.
