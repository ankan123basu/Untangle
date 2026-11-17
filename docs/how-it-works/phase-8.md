# How it works — Phase 8: The screens

## Simple explanation

Untangle is now usable end to end in the browser. The dashboard shows what needs attention, the
ticket list can be filtered and the exact view shared as a link, and a ticket can be worked from
start to finish: assign, start, comment, resolve with a note, close and reopen. Leads get a board
view and workload numbers; admins manage agents, partners and webhook logs. Buttons only appear
when the server says the action is possible, and a greyed-out button tells you why.

## Technical details

### Shared building blocks (`features/tickets`)

| File | Role |
|---|---|
| `filters.ts` | the only translation between URL and API query; drops values the API would reject; quick views are just preset URLs |
| `ticket-api.ts` | TanStack Query hooks; every ticket write stores the returned detail in the cache and refreshes lists + dashboard; assignment and comments are optimistic with rollback |
| `ticket-meta.ts` | labels and "what input does this action need" (note / reason); no business rules: *whether* an action is allowed always comes from `allowedActions` |
| `badges.tsx` | priority / status / escalation / source badges (always text, never colour alone) and the live SLA countdown |
| `ticket-browser.tsx` | the URL-synced table used by the dashboard and `/tickets` |

### Ticket detail flow

1. Load the ticket (with `version`, `allowedActions`, `deniedActions`) and its timeline.
2. Action bar: allowed → enabled; denied for a fixable reason (role, needs an assignee) → shown
   disabled with the reason as tooltip and accessible description; impossible from this status →
   hidden.
3. Actions needing text (resolve note, reasons for close-from-open, back-to-progress, reopen,
   escalate) open one `ActionDialog`; if the server rejects, the dialog stays open so nothing typed
   is lost.
4. Every write sends the loaded `version`. `409 VERSION_CONFLICT` opens "This ticket changed" →
   "Load latest". Other errors become friendly toasts via `messageFor(code)`.
5. Resolving plays a small spark burst (skipped under reduced motion).

### Board (bonus)

Columns are four list queries. On drop (or "Move to…"), the board fetches the ticket's detail and
checks `allowedActions` before calling the API. A disallowed move changes nothing and shows the
server's reason ("Assign someone first."), which is the "snap back".

### Performance

Every route is its own chunk. Recharts (~119 kB gzipped) is split again from the dashboard so the
numbers paint first: dashboard chunk 15 kB gzipped.

### Bugs found while verifying

| Found by | Problem | Fix |
|---|---|---|
| tests | all dialogs shared `id="dialog-title"`; with several dialogs on a page, screen readers announced the wrong title | `useId()` per dialog |
| browser | dialogs opened with focus on the close button, so typing went nowhere | `Dialog` focuses the first field after `showModal()` |
| browser | "Created" column clipped "47 minutes ago" | wider column |
| browser | lazy routes showed no feedback while loading | progress bar from `useNavigation()` |
| lint (React Compiler) | `form.watch` and `Date.now()` during render | `useWatch`; time captured once in state |

### Verified in the browser (Docker stack, seeded data)

Login → dashboard (38 unresolved, KPIs, chart, workload) → filtered list from URL → ticket →
assign to me → start → comment containing HTML (shown as text) → resolve with note (SLA stops) →
close (read-only banner) → reopen (SLA restarts). Board refuses moving an unassigned ticket to In
progress with "Assign someone first.". Admin agents, partners and webhook logs load real data.
Phone width: drawer navigation, no horizontal scroll.

### Not covered here

AI panels were tested with mocks only (AI is off in Docker without a key). Drag-and-drop itself
can't be driven by the browser automation; the same code path is exercised via "Move to…".
