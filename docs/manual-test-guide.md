# Manual test guide

## Simple explanation

A checklist to try every feature of Untangle by hand, in about 30 minutes, and see each original
requirement working. Do the sections in order; each says what to do and what you should see.

## Technical details

### 0. Start

```bash
make up          # app http://localhost:3000, API http://localhost:8080
```

Demo accounts (password for all: `Untangle@2026`), or use the Admin / Lead / Agent buttons on the
login page:

| Role | Email | Can |
|---|---|---|
| Admin | `admin@untangle.local` | everything, incl. agents, partners, webhook logs |
| Lead | `leela.thomas@untangle.local` | assign anyone, close, reopen, see workload |
| Agent | `riya.sen@untangle.local` | work own tickets, assign self, comment, resolve |

Fresh demo data at any time: `make nuke` then `make up` (deletes the local database).

### 1. Sign-in and session

| Do | Expect |
|---|---|
| Sign in with a wrong password | "That email and password don’t match." |
| Wrong password 5 times for one account | account locked, with the unlock time |
| Sign in correctly, reload the page | still signed in (refresh cookie) |
| Sign out, press Back | login page, not the app |

### 2. Create a ticket (requirement 1, rule "valid priority")

As **Agent**: New ticket.

| Do | Expect |
|---|---|
| Submit empty | inline errors on subject, description, customer name |
| Subject "Hi" | "At least 5 characters" |
| Valid ticket, priority High, tick "Assign to me" | ticket page opens with a new `UNT-000xxx`, priority High, SLA countdown |
| Swagger (`/swagger-ui.html`) POST `/api/v1/tickets` with `"priority":"URGENT"` | 400 with `errors[priority]`: invalid priorities are impossible |

### 3. Assign (requirement 2)

| Do | Expect |
|---|---|
| As **Lead**, open an unassigned ticket, pick an agent in Details → Assignee | toast, timeline "Assigned to …"; the picker shows each agent's open count |
| As **Agent**, open someone else's ticket | no assignee picker; "Assign to me" only if it is unassigned and Open |

### 4. Status lifecycle (requirement 3)

On your ticket as **Agent**:

| Do | Expect |
|---|---|
| Unassigned ticket | "Start progress" disabled, tooltip "Assign someone first." |
| Start progress | stepper moves to In progress |
| Resolve | dialog asks for a resolution note (required); SLA shows "SLA stopped" |
| Close (as agent) | disabled: "Your role can’t do this on this ticket." |
| As **Lead**: Close | yellow-grey banner "This ticket is closed and read-only." |

### 5. Closed tickets can't change (business rule)

| Do | Expect |
|---|---|
| Closed ticket | no action bar, no comment box, no assignee picker |
| Swagger: POST a comment to the closed ticket | 409 `TICKET_CLOSED` |
| As **Lead**: Reopen with a reason | status Open, SLA restarts, timeline "Ticket reopened" |

### 6. Comments and resolution notes (requirement 4)

| Do | Expect |
|---|---|
| Type a comment, press Ctrl/⌘ + Enter | appears at once in the timeline |
| Comment `<b>hi</b><img src=x onerror=alert(1)>` | shown as plain text, nothing runs |
| Resolve with a note | green "Resolution note" entry |

### 7. Filters (requirement 5)

On **Tickets**:

| Do | Expect |
|---|---|
| Chips: Critical + Open | only open critical tickets; URL shows `?status=OPEN&priority=CRITICAL` |
| Assignee dropdown: an agent / Unassigned | list follows |
| Search `UNT-000041` or "refund" | matching tickets |
| Copy the URL into a new tab | same filtered view |
| Browser Back | previous filter |
| Quick views: My open, Unassigned critical, Breached, Escalated | presets |

### 8. Dashboard (requirement 6)

| Do | Expect |
|---|---|
| Open Dashboard | big **Unresolved** number = Open + In progress |
| Click a KPI card (SLA breached, Unassigned…) | ticket list with that filter; its total equals the card |
| Resolve a ticket, return | the number drops (cache is refreshed on every change) |
| As **Agent** | no "Agent workload" card |

### 9. HMAC webhook + duplicate events (HMAC requirement, rule "no duplicates")

```bash
make simulate                      # 13 partner scenarios, all should PASS
```

Covers: valid signature → 201; same event id again → 200 with the **same** ticket; 20 parallel
retries → exactly one ticket; wrong secret, tampered body, old timestamp, unknown partner → 401;
invalid priority with a valid signature → 400; > 64 KB → 413; burst → 429; old secret during
rotation → still accepted.

Then as **Admin** → Webhook logs: every attempt with its outcome and reason; created ones link to
their ticket. In the ticket list those tickets carry a "Partner" badge.

### 10. Admin

| Do | Expect |
|---|---|
| Agents → Add agent (password ≥ 10 chars) | new agent can sign in |
| Change a role | takes effect on their next request |
| Deactivate an agent who owns open tickets | "move their tickets" dialog, then deactivated |
| Partners → Add partner | secret shown **once** with a copy button; gone after closing |
| Rotate secret | old secret keeps working for 24 h |

### 11. Extras

| Feature | How |
|---|---|
| Board | Board: drag a card (desktop) or "Move to…" (phone); not-allowed moves explain why |
| Escalation | Escalate with a reason; leads get a notification (bell) |
| SLA auto-escalation | wait for a ticket's SLA to pass: within a minute it is escalated and leads are notified |
| Command palette | Ctrl/⌘ + K, type `41` → jumps to UNT-000041; `c` new ticket, `g d` dashboard |
| Theme | header button cycles light / dark / system |
| Phone | open on a phone (same Wi-Fi: `http://<your-computer-ip>:3000`) or browser dev tools device mode |
| AI | set `AI_ENABLED=true` and `GROQ_API_KEY=…` in `.env`, `make up`: "Suggest with AI" on create, Summarise / Draft note on a ticket |
| Metrics | `docker compose --profile observability up -d`, Grafana http://localhost:3001 (admin / `GRAFANA_ADMIN_PASSWORD` from `.env`) |

### CRUD summary

| Entity | Create | Read | Update | Delete |
|---|---|---|---|---|
| Ticket | New ticket / webhook | list, filters, detail | edit, assign, status, escalate, reopen | not allowed: tickets are closed, never deleted (audit) |
| Comment | composer | timeline | not allowed (append-only, ADR-010) | not allowed |
| Agent | Admin → Add agent | Admin → Agents | role (name via the API) | deactivate (soft) |
| Partner | Admin → Add partner | Admin → Partners | rotate secret, enable/disable | disable (soft) |

### Automated equivalents

`make test` (unit + integration), `make e2e` (browser journeys), `make check-features` (every
feature through the API + a live RBAC matrix, 103 checks), `make simulate` (webhook),
`make lighthouse`, `make load`.
