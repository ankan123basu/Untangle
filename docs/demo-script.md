# Demo script (10 minutes)

## Simple explanation

A spoken walkthrough for showing Untangle to a lead or reviewer: what to click, in what order, and
the one sentence to say at each step. For the exhaustive checklist see
[manual-test-guide.md](manual-test-guide.md).

## Technical details

**Before the demo** (2 min, off-screen): `make nuke && make up` for fresh data, then
`make simulate` once so the webhook log has entries. Optional: `AI_ENABLED=true` + `GROQ_API_KEY`
in `.env`. Open http://localhost:3000 in a desktop browser.

| Time | Do | Say |
|---|---|---|
| 0:00 | Login page: let the knot untangle | "Every complaint, untangled: a tangled complaint becomes a resolved ticket. The 3D is only here; the work screens stay fast." |
| 0:30 | Click **Lead**, Sign in | "Three roles. Sign-in uses short-lived tokens plus a rotating refresh cookie." |
| 1:00 | Dashboard: unresolved number, KPI cards, trend, workload | "The number that matters. Every card links to the exact filtered list, and the numbers always match it." |
| 2:00 | Click **SLA breached** card | "Filters live in the URL: shareable, back button works." |
| 2:30 | Open a breached critical ticket | "Live SLA countdown, escalation level, a timeline that mixes comments and system events." |
| 3:00 | Hover a disabled action | "The server decides what you may do; disabled buttons say why." |
| 3:30 | Assign to an agent, Start progress, add a comment with Ctrl+Enter | "Every change is checked server-side and recorded." |
| 4:30 | Resolve (note required), Close | "Resolving needs a note. Closed is read-only..." |
| 5:00 | Try to comment on the closed ticket (box is gone), then Reopen with a reason | "...until a lead reopens it with a reason; the SLA restarts." |
| 5:30 | AI panel: Summarise (if enabled) | "AI only suggests. It never changes data, and personal details are removed before sending." |
| 6:00 | Terminal: `make simulate` (or show its table) | "A partner sends complaints through an HMAC-signed webhook: bad signatures, replays, oversized bodies and floods are rejected; a retried event never creates a second ticket, even 20 at once." |
| 7:00 | Sign out, sign in as **Admin** → Webhook logs | "Partners only ever see a generic 401; admins see the exact reason for every attempt." |
| 7:30 | Partners → Rotate secret | "Secrets are encrypted at rest, shown once, and rotate with a 24-hour grace period." |
| 8:00 | Sign in as **Agent** (Riya): no admin menu; Close is disabled | "Role-based access, enforced by the API; the UI only reflects it." |
| 8:30 | Dark mode toggle; phone width (dev tools) | "Light and dark, works on phones, keyboard and screen-reader friendly." |
| 9:00 | README: architecture diagram + tests table | "Modular monolith, 24 decision records, 400+ backend tests on a real PostgreSQL, browser tests, a live RBAC check, load tests and Lighthouse 91–100." |
| 9:30 | Questions | Have [tradeoffs.md](tradeoffs.md) and [scalability.md](scalability.md) open. |

**If something goes wrong**: every error shows a reference (`traceId`);
`docker compose logs backend --no-log-prefix | grep <traceId>` finds the exact request.
