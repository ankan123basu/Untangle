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

### Auth / RBAC (Phase 2)

Test classes: `AuthIntegrationTest` (A), `AgentAdminIntegrationTest` (G),
`EndpointAuthorizationMatrixTest` (M), `RolePermissionsTest` (R), `SecurityPropertiesTest` (S),
`ErrorHandlingIntegrationTest` (E).

| Case | Expected | Test | Status |
|---|---|---|---|
| Wrong password | 401 `AUTH_INVALID_CREDENTIALS` | A `wrongPasswordAndUnknownEmailAreIndistinguishable` | ✅ |
| Unknown email | same body as wrong password | A (same test) | ✅ |
| 5 failed attempts | 423 `AUTH_ACCOUNT_LOCKED`; right password still refused | A `accountLocksAfterFiveFailedAttemptsAndRejectsEvenTheRightPassword` | ✅ |
| Lock expires | login works, lock cleared | A `loginWorksAgainOnceTheLockExpires` | ✅ |
| Success resets failure count | counter 0 | A `successfulLoginResetsTheFailureCounter` | ✅ |
| Login flood per email | 429 + `Retry-After` | A `loginIsRateLimitedPerEmail` | ✅ |
| Expired access token | 401 `AUTH_TOKEN_EXPIRED` (client refreshes) | A `expiredAccessTokenIsReportedAsExpired` | ✅ |
| Tampered / foreign-key token | 401 | A `tamperedAccessTokenIsRejected`, `tokenSignedWithAnotherKeyIsRejected` | ✅ |
| Refresh rotates | new access token + new cookie; old marked replaced | A `refreshRotatesTheTokenAndIssuesANewAccessToken` | ✅ |
| Refresh token reuse | 401 `AUTH_REFRESH_REUSED`, whole family revoked | A `reusingARotatedRefreshTokenRevokesTheWholeSession` | ✅ |
| Expired / missing refresh token | 401 `AUTH_REFRESH_INVALID` | A `expiredRefreshTokenIsRejected`, `refreshWithoutCookieIsRejected` | ✅ |
| Logout | 204, cookie cleared, refresh dead | A `logoutRevokesTheSessionAndClearsTheCookie` | ✅ |
| Deactivated agent cannot log in | 401 `AUTH_ACCOUNT_DISABLED` | A `deactivatedAgentCannotLogIn` | ✅ |
| **Deactivated agent with a still-valid JWT** | 401 on next request; sessions revoked | A `deactivatedAgentIsRejectedEvenWithAStillValidToken` | ✅ |
| Role change while token valid | new permissions apply | A `roleChangesApplyWithoutReissuingTheToken` | ✅ |
| AGENT/LEAD on admin APIs | 403 `ACCESS_DENIED` (before body validation) | M `enforcesThePermissionMatrix` (18 cases) | ✅ |
| Anonymous on protected APIs | 401 | M `anonymousCallersAreRejected` | ✅ |
| Full §6.2 permission table | matches cell by cell | R `matchesTheAgreedMatrix` (19 rows) | ✅ |
| Last admin demote | 409 `LAST_ADMIN` | G `lastActiveAdminCannotBeDemoted`, `lastAdminRuleIgnoresDeactivatedAdmins` | ✅ |
| Deactivate yourself | 409 `CANNOT_DEACTIVATE_SELF` | G `adminCannotDeactivateThemselves` | ✅ |
| Deactivate agent with open tickets | 409 `AGENT_HAS_OPEN_TICKETS` | G `agentWithUnresolvedTicketsCannotBeDeactivated` | ✅ |
| Duplicate email (any case) | 409 `EMAIL_TAKEN` | G `rejectsDuplicateEmailIgnoringCase` | ✅ |
| Weak password / unknown role | 400 with field, value not echoed | G `rejectsPasswordsShorterThanTenCharacters`, `rejectsUnknownRole` | ✅ |
| Missing / short / non-base64 JWT secret | app refuses to start | S (5 tests) | ✅ |
| Malformed JSON | 400, no parser internals | E `malformedJsonIsA400WithoutParserInternals` | ✅ |
| Log injection via `X-Request-Id` | id replaced | E `unsafeRequestIdsAreReplaced` | ✅ |
| Two admins demoting each other at once | one fails with `LAST_ADMIN` (row lock) | design: `lockActiveByRole` `FOR UPDATE` | ⚠️ not yet a concurrent test (Phase 10) |

### Dashboard, AI

Phases 3 and 6.
