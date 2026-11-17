# How it works — Phase 7: Frontend foundation

## Simple explanation

This phase builds everything the screens will stand on: the look (colours, outlines, shadows, dark
mode), reusable building blocks (buttons, fields, cards, dialogs), signing in and staying signed in,
talking to the API, and the app frame (sidebar, header, search palette, notification bell). The
actual ticket screens come in Phase 8; for now they are placeholders inside the real frame.

## Technical details

### Session handling

- The **access token lives only in memory** (zustand store). The refresh token is the backend's
  `HttpOnly` cookie, which JavaScript can't read.
- On first load `AuthBootstrap` calls `POST /auth/refresh` once. Success → signed in again (so a
  reload keeps you signed in); 401 → anonymous. A splash shows while this happens.
- `RequireAuth` sends anonymous users to `/login` and remembers where they wanted to go;
  `RequirePermission` sends users without the permission to `/403`. These are UX only: the server
  enforces every rule.

### API client (`lib/api/client.ts`)

- Axios with base URL `/api/v1` (same origin: nginx in Docker, the Vite proxy in dev).
- Adds `Authorization` and an `X-Request-Id`, which the backend logs.
- On a 401 it refreshes **once** even when many requests fail at the same moment (single-flight
  promise), then retries each request once. If the refresh fails, the session is cleared.
- Every error becomes an `ApiError` with `status`, `code`, `traceId` and field errors parsed from
  RFC 9457; `messageFor` turns codes into friendly text.

### Types from the backend

`make api-types` downloads `/v3/api-docs` and regenerates `src/lib/api/schema.d.ts`. To make those
types exact, the backend now marks every response field as required and nullable fields as
`T | null` (ADR-023). `lib/api/types.ts` gives the generated types short names.

### App shell

Sidebar (admin links only with permission), header with the command palette trigger, notification
bell (polls unread count every 30 s), theme toggle, offline banner. Palette shortcuts: `⌘/Ctrl K`
or `/` to open, `c` new ticket, `g d` dashboard, `g t` tickets; typing `UNT-123` jumps to a ticket.

### Tests (15)

Vitest + Testing Library + MSW 3 (`onUnhandledFrame: 'error'`, so a missing mock fails the test):
single-flight refresh with three concurrent 401s, sign-out when refresh fails, Problem Details
parsing, login validation/success/wrong credentials/locked account, both route guards, and
accessibility wiring of `Field` and `Button`.

### Bugs found while verifying

| Found by | Problem | Fix |
|---|---|---|
| browser check on Docker | Vite inlined small font files as `data:` URIs; the CSP (`font-src 'self'`) blocked them | `assetsInlineLimit` never inlines fonts |
| generated types | every field optional | precise OpenAPI contract (ADR-023) |
| jsdom | no `IntersectionObserver` (ShinyText) or `<dialog>` methods | minimal stubs in `src/test/setup.ts` |
| shadcn CLI | wrote files into a literal `@/` folder and changed the `motion` pin | moved files, re-pinned 14.14.0 |

### Verified

| Check | Result |
|---|---|
| `make ci` | green: 392 backend tests, 15 frontend tests, lint, typecheck, build |
| Docker stack | login → returned to the requested page; reload keeps the session; admin page visible to admin |
| CSP | no violations after the font fix; all three fonts load |

### Known gaps (next phases)

- Below ~1024 px the sidebar stacks above the content; Phase 8 turns it into a drawer.
- Login hero is a static brand panel; the 3D knot is Phase 9.
