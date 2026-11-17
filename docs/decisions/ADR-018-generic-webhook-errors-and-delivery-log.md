# ADR-018: Generic 401 bodies for partners; precise reasons in a delivery log for admins

- **Status:** Accepted
- **Date:** 2026-11-18
- **Phase:** 4

## Context

When a webhook is rejected, the caller could be a misconfigured partner or an attacker probing.
Detailed errors ("unknown partner", "signature mismatch", "timestamp expired") help both.

## Options considered

1. **Detailed 401s.** Easiest for partners to debug, but leaks which partner codes exist and which
   checks pass.
2. **Generic 401s, no log.** Safe, but impossible to debug.
3. **Generic 401 to the caller + a precise reason per attempt in `webhook_deliveries`, visible to
   admins.**

## Decision

Option 3. Every attempt, accepted or rejected, gets one row: outcome, reason, partner, event id, IP,
SHA-256 of the body (never the body itself: PII minimisation) and the linked ticket. Rows are written
in their own transaction (`REQUIRES_NEW`), so they survive the request's rollback. All 401s share one
body: `code: WEBHOOK_REJECTED`, `detail: "invalid webhook request"`. 400s (bad data with a valid
signature) are detailed, because the caller is authenticated by then.

Related decision (A8, also here): a duplicate event returns **200** with the original ticket and
`duplicate: true`, not 409, because partners usually treat non-2xx as "retry", and a retry storm
helps nobody.

## Consequences

- **Pros:** no probing oracle; admins can debug any integration from the log; duplicates are
  visible (including "payload differs from the original", R3).
- **Cons:** partners need to contact an admin for details; the log grows (30-day retention job in
  Phase 5).
- **Tests:** `WebhookIntegrationTest.assertGeneric401` (body never mentions signature/timestamp)
  plus delivery assertions per outcome.

## Simple explanation

Outsiders get a plain "rejected". Our admins see exactly why, in a log, without us ever storing the
customer's message itself.
