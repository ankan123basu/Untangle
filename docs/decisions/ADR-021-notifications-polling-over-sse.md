# ADR-021: Notifications are delivered by polling the unread count (SSE deferred)

- **Status:** Accepted
- **Date:** 2026-11-18
- **Phase:** 5

## Context

§9 lists `GET /notifications/stream` (SSE) as a bonus; §12.5 says "SSE or polling". The API
authenticates with a bearer token held in memory (ADR-013). The browser's `EventSource` can't send
an `Authorization` header, so SSE would need either a token in the URL (it leaks into logs and
history) or a cookie-authenticated endpoint (a second auth path).

## Options considered

1. **SSE with the token in the query string.** Simple, but leaks the credential.
2. **SSE via `fetch` streaming with a header.** Works, but adds reconnect/backoff code and keeps one
   long-lived connection per tab, which the nginx/backend setup must be tuned for.
3. **Poll `GET /notifications/unread-count` every 30 s** (plus refetch on window focus) and load the
   drawer on open.

## Decision

Option 3 for now. The count query hits the partial index `ix_notifications_unread`, so it's cheap.
nginx is already configured for SSE (`proxy_buffering off`, long read timeout), so adding option 2
later needs no infrastructure change.

## Consequences

- **Pros:** no credential leakage, no long-lived connections, trivial to scale horizontally.
- **Cons:** up to 30 s delay for a new notification; a few small requests per minute per open tab.

## Simple explanation

Every 30 seconds the app asks "anything new for me?". It's slightly less instant than a live push,
but simpler and safer.

## Update (Phase 11, after live testing)

Tested live with two users: assignment, escalation, reopen and SLA-breach notifications all reach
the right people (never the actor) and appear without a reload. Changes made:

- Bell polls every **15 s** (was 30 s); new notifications pop up as a toast with an **Open** button
  and immediately refresh ticket data on screen.
- Open screens also refresh themselves: ticket detail and timeline every **10 s**, ticket lists
  and the board every **20 s**, team workload every **30 s**, dashboard numbers every **60 s**.
  TanStack Query pauses all of this while the tab is hidden and refetches on focus.
- The edit dialog keeps what the user typed when a background refresh arrives
  (`keepDirtyValues`).
- Signing out (or another user signing in) now clears the cached data, so the next user never
  sees the previous user's numbers (bug found during this test).

Push (SSE behind Redis pub/sub) remains the documented upgrade path ([scalability.md](../scalability.md)).

