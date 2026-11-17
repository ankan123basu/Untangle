# Edge cases

## Simple explanation

Every tricky situation from the brief (MASTER_PROMPT §11), what Untangle does about it, and the
automated test that proves it. A case is only ✅ when its test exists and passes. Many rules are
tested at two levels: the database constraint now (Phase 1), and the API behaviour when the
endpoint is built.

## Technical details

Test classes live in `backend/src/test/java/dev/untangle/`.

### Webhook / HMAC (API behaviour: Phase 4)

| Case | Expected | Test | Status |
|---|---|---|---|
| Duplicate event id, sequential | second insert rejected by DB | `SchemaConstraintsIntegrationTest.rejectsSameEventIdTwiceFromSamePartner` | ✅ DB |
| Duplicate event id, 20 concurrent requests | exactly 1 row | `ConcurrencyIntegrationTest.twentyConcurrentInsertsOfOneEventCreateExactlyOneTicket` | ✅ DB |
| Same event id from two partners | both allowed | `SchemaConstraintsIntegrationTest.allowsSameEventIdFromDifferentPartners` | ✅ DB |
| Webhook ticket without event id / manual ticket with one | rejected | `rejectsWebhookTicketWithoutEventId`, `rejectsManualTicketCarryingAnEventId` | ✅ DB |
| Delivery logged as CREATED without its ticket | rejected | `createdDeliveryMustLinkItsTicket` | ✅ DB |
| Malformed partner code | rejected | `rejectsMalformedPartnerCodes` | ✅ DB |
| Previous secret without expiry | rejected | `previousSecretRequiresAnExpiry` | ✅ DB |
| Missing headers, malformed signature, wrong secret, tampered body, whitespace change, expired/future/non-numeric timestamp, unknown/inactive partner, invalid priority (400), malformed JSON (400), oversized (413), rate limit (429), rotation grace in/out, no encryption key | see §7 | — | Phase 4 |

### Lifecycle (API behaviour: Phase 3)

| Case | Expected | Test | Status |
|---|---|---|---|
| CLOSED without `closed_at` | rejected by DB | `rejectsClosedTicketWithoutClosedAt` | ✅ DB |
| RESOLVED without `resolved_at` | rejected by DB | `rejectsResolvedTicketWithoutResolvedAt` | ✅ DB |
| IN_PROGRESS without an assignee | rejected by DB | `rejectsInProgressTicketWithoutAssignee` | ✅ DB |
| Comment edited after the fact | rejected (append-only) | `commentsCannotBeEdited` | ✅ DB |
| Human comment without author | rejected | `humanCommentsRequireAnAuthor` | ✅ DB |
| Every allowed/disallowed transition, same-status, resolve without note, each mutation on CLOSED → 409, reopen rules, SLA recalculation, scheduler idempotency | see §5 | — | Phases 3, 5 |

### Concurrency

| Case | Expected | Test | Status |
|---|---|---|---|
| Two updates with the same version | first wins, second fails | `ConcurrencyIntegrationTest.staleUpdateIsRejectedByOptimisticLocking` | ✅ DB (→ 409 in Phase 3) |
| Two agents self-assigning at once | one wins | — | Phase 3 |

### Validation

| Case | Expected | Test | Status |
|---|---|---|---|
| Priority `high`, `URGENT`, empty, `Critical`, `P1` | rejected | `rejectsInvalidPriority` (parameterised) | ✅ DB |
| Null priority | rejected | `rejectsNullPriority` | ✅ DB |
| All four valid priorities | accepted | `acceptsEveryValidPriority` (parameterised) | ✅ DB |
| Unknown status / role | rejected | `rejectsUnknownStatus`, `rejectsUnknownRole` | ✅ DB |
| Description shorter than 10 chars | rejected | `rejectsTooShortDescription` | ✅ DB |
| Duplicate agent email in different case | rejected | `agentEmailsAreUniqueIgnoringCase` | ✅ DB |
| 400 field errors, HTML in comments, unknown filters, empty results, page size > 100 | see §5.3/§9 | — | Phase 3 |

### Data integrity extras (added in Phase 1)

| Case | Expected | Test | Status |
|---|---|---|---|
| Ticket number past 999 999 | `UNT-1000000`, no truncation | `ticketNumbersDoNotTruncatePastSixDigits` | ✅ |
| Manual ticket without creator | rejected | `rejectsManualTicketWithoutCreator` | ✅ |
| Demo seed violates no constraint and covers every state | loads cleanly | `DemoSeedIntegrationTest` (5 tests) | ✅ |

### Auth/RBAC, Dashboard, AI

Phases 2, 3 and 6.
