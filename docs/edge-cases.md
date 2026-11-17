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
| Every one of the 16 from→to pairs | allowed exactly per §5.1 | `TicketStateMachineTest.matchesTheTransitionTable` (16) | ✅ |
| Full happy path incl. reopen | timeline has every step | `TicketLifecycleIntegrationTest.fullLifecycleFromIntakeToReopen` | ✅ |
| Disallowed transitions (6 cases incl. same-status) | 409 `INVALID_STATUS_TRANSITION` | `rejectsDisallowedTransitions` | ✅ |
| IN_PROGRESS without an assignee | 409 `ASSIGNEE_REQUIRED` | `cannotStartWorkOnAnUnassignedTicket` | ✅ |
| Resolve without a note (blank counts) | 400 `RESOLUTION_NOTE_REQUIRED` | `resolvingRequiresANote` | ✅ |
| OPEN→CLOSED / RESOLVED→IN_PROGRESS without reason | 400, `errors[reason]` | `closingAnOpenTicketNeedsAReason`, `returningToProgressNeedsAReason` | ✅ |
| Each mutation on CLOSED (edit, assign, comment, escalate, 2 transitions) | 409 `TICKET_CLOSED` | `closedTicketsRejectEveryMutation` (6) + policy tests | ✅ |
| Reopen a non-closed ticket / without reason | 409 / 400 | `reopeningANonClosedTicketIs409AndNeedsAReason` | ✅ |
| Reopen when the previous assignee is deactivated | assignee cleared (R5) | `reopenDropsADeactivatedAssignee` | ✅ |
| SLA recalculated on priority change | same start, new target | `priorityChangeRecalculatesTheSlaFromTheSameStart` | ✅ |
| Release (IN_PROGRESS→OPEN) | assignee cleared | `releaseClearsTheAssignee` | ✅ |
| Agent acting on another agent's ticket | 403 (comment still allowed) | `agentsCannotWorkOtherAgentsTickets` + policy tests | ✅ |
| Unassign non-OPEN / assign to deactivated agent | 409 `ASSIGNEE_REQUIRED` / `AGENT_INACTIVE` | `assignmentRules` | ✅ |
| `allowedActions` per role, ownership, status | matches policy | `allowedActionsReflectRoleOwnershipAndState`, `TicketAccessPolicyTest` (37) | ✅ |
| Scheduler escalates once per level | idempotent | — | Phase 5 |

### Concurrency

| Case | Expected | Test | Status |
|---|---|---|---|
| Two updates with the same version | first wins, second fails | `ConcurrencyIntegrationTest.staleUpdateIsRejectedByOptimisticLocking` | ✅ DB |
| Stale `version` over HTTP | 409 `VERSION_CONFLICT` + `currentVersion` | `TicketLifecycleIntegrationTest.staleVersionIsAConflict` | ✅ |
| Two agents self-assigning at once | one 200, one 409 | `twoAgentsSelfAssigningAtOnceOnlyOneWins` | ✅ |

### Validation

| Case | Expected | Test | Status |
|---|---|---|---|
| Priority `high`, `URGENT`, empty, `Critical`, `P1` | rejected | `rejectsInvalidPriority` (parameterised) | ✅ DB |
| Null priority | rejected | `rejectsNullPriority` | ✅ DB |
| All four valid priorities | accepted | `acceptsEveryValidPriority` (parameterised) | ✅ DB |
| Unknown status / role | rejected | `rejectsUnknownStatus`, `rejectsUnknownRole` | ✅ DB |
| Description shorter than 10 chars | rejected | `rejectsTooShortDescription` | ✅ DB |
| Duplicate agent email in different case | rejected | `agentEmailsAreUniqueIgnoringCase` | ✅ DB |
| Priority `high` / `URGENT` / null / `""`, unknown category, blank customer name, short subject, bad email | 400 with that field | `TicketLifecycleIntegrationTest.rejectsInvalidTickets` (8) | ✅ |
| Description > 5000 chars | 400 | `rejectsADescriptionOver5000Characters` | ✅ |
| HTML/script in a comment | stored verbatim, served as JSON text (`nosniff`); UI renders as text (Phase 8 test) | `commentsAreStoredVerbatimAndServedAsJsonText` | ✅ API |
| SYSTEM comment via API | 400 | `systemCommentsCannotBeCreatedThroughTheApi` | ✅ |
| Unknown filter values, bad sort, bad dates, page < 0 | 400 with field | `TicketQueryIntegrationTest.rejectsInvalidParameters` (8) | ✅ |
| Page size > 100 | **rejected** with 400 (documented choice: clients learn instead of silently getting fewer rows) | same | ✅ |
| Empty filter results | 200, empty `content` | `filtersNewestFirstByDefault` (last rows) | ✅ |
| `%`/`_` in search | treated literally | `filtersNewestFirstByDefault` (`q=100%`, `q=_`) | ✅ |

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

### Dashboard (Phase 3)

| Case | Expected | Test | Status |
|---|---|---|---|
| Zero tickets | zeros for every key, not errors | `DashboardIntegrationTest.zeroTicketsMeansZerosNotErrors` | ✅ |
| Counts match the filtered list exactly | unresolved, breached, escalated, unassigned, mine, by status, by priority | `everyCountMatchesTheEquivalentListFilter` | ✅ |
| Trend days without tickets | present with 0 | `trendsCoverEveryDayIncludingEmptyOnes` | ✅ |
| Workload for agents | 403 | `workloadIsForLeadsAndCountsPerAgent` | ✅ |

### AI

Phase 6.
