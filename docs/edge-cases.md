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
| Missing headers (each of 4) | generic 401 | `WebhookIntegrationTest.rejectsMissingHeaders` | ✅ |
| Malformed signature header | generic 401 | `rejectsMalformedSignatureHeader`, `WebhookCryptoTest.rejectsMalformedSignatureHeaders` | ✅ |
| Wrong secret | generic 401; log: "signature mismatch" | `rejectsWrongSecret` | ✅ |
| Tampered body | 401 | `rejectsATamperedBody` | ✅ |
| Whitespace-only change to the body | 401 (raw bytes are signed) | `rejectsAWhitespaceOnlyChange`, `WebhookCryptoTest.anyChangeBreaksTheSignature` | ✅ |
| Expired / future / non-numeric timestamp | 401; log says past/future/non-numeric | `rejectsExpiredFutureAndNonNumericTimestamps` | ✅ |
| Unknown or inactive partner | 401; log distinguishes them | `rejectsUnknownAndInactivePartners` | ✅ |
| Duplicate event id (sequential) | 201 then 200, same ticket | `duplicateEventReturns200WithTheSameTicket` | ✅ |
| **Duplicate event id, 20 concurrent HTTP requests** | exactly 1 row; 1×201 + 19×200, one ticket id | `twentyConcurrentRetriesCreateExactlyOneTicket` + simulator `concurrent-duplicate` | ✅ |
| Same event id, different payload (R3) | 200 + log "payload differs" | `duplicateWithADifferentPayloadIsFlaggedForAdmins` | ✅ |
| Same event id from two partners | both 201 | `sameEventIdFromTwoPartnersCreatesTwoTickets` | ✅ |
| Valid signature + invalid priority | 400 `errors[priority]` | `validSignatureButInvalidPriorityIs400` | ✅ |
| Valid signature + missing fields | 400 with fields | `validSignatureButMissingFieldsIs400` | ✅ |
| Malformed JSON | 400 | `malformedJsonIs400` | ✅ |
| Missing subject | derived from description | `missingSubjectIsDerivedFromTheDescription` | ✅ |
| Oversized payload (> 64 KB) | 413 before parsing; logged | `oversizedPayloadIs413BeforeAnythingElse` | ✅ |
| Rate limit | 429 + `Retry-After` | `perPartnerRateLimitReturns429WithRetryAfter` + simulator `burst` | ✅ |
| Old secret inside / outside the grace window | 201 / 401 | `oldSecretWorksInsideTheGraceWindowOnly` + simulator `rotation` | ✅ |
| Server started without an encryption key | refuses to start | `WebhookCryptoTest.refusesToStartWithoutAValid256BitKey` | ✅ |
| Secret stored encrypted, shown once | ciphertext `v1:...` only | `PartnerAdminIntegrationTest.secretIsShownOnceAndStoredOnlyEncrypted` | ✅ |
| Ciphertext copied to another partner / tampered | decryption fails | `WebhookCryptoTest.ciphertextIsBoundToItsPartner`, `tamperingAndWrongKeysAreDetected` | ✅ |

| Exact replay of a signed request (same event id + timestamp) | 201 then 200, one ticket | `anExactReplayCreatesNoSecondTicket`, simulator `replay` | ✅ |
| Valid signature reused with a different (fresh) timestamp | 401, "signature mismatch" | `theTimestampIsBoundToTheSignature`, simulator `timestamp-swap` | ✅ |
| Body signed with another partner's secret | 401 | `aSignatureFromAnotherPartnersSecretIsRejected`, simulator `cross-partner` | ✅ |
| Uppercase hex signature | accepted (prefix stays exact) | `acceptsAnUppercaseHexSignature`, `acceptsUppercaseHex` | ✅ |
| Non-ASCII body (Hindi, emoji) | signed over exact UTF-8 bytes; stored intact | `nonAsciiTextIsSignedOverTheExactUtf8Bytes`, simulator `unicode` | ✅ |
| Millisecond timestamp | 401; log says "looks like milliseconds" | `aMillisecondTimestampIsRejectedWithAClearReason` | ✅ |

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
| SLA levels: due at n × target, capped at 3; resolved tickets never breach | levels 0/1/2/3 | `SlaEscalationIntegrationTest.levelsFollowMultiplesOfTheTargetAndAreCapped` | ✅ |
| **Scheduler escalates once per level (re-runs)** | second run is a no-op; the next level follows later | `rerunningIsIdempotentAndLaterLevelsFollow` | ✅ |
| **Two instances running the scan at once** | each ticket escalated exactly once | `twoConcurrentRunsEscalateEachTicketOnce` (row locks) + `shedLockLetsOnlyOneInstanceHoldTheJobLock` | ✅ |
| Manual escalation above the due level | kept, not repeated | `manualEscalationAboveTheDueLevelIsNotDowngradedOrRepeated` | ✅ |
| SLA breach notifications | leads + assignee | `breachNotifiesLeadsAndTheAssignee` | ✅ |
| Notifications: no self-notification; others' notifications → 404 | | `NotificationIntegrationTest` (3) | ✅ |

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
| HTML/script in a comment | stored verbatim, served as JSON text (`nosniff`); UI renders it as text, no element is created | `commentsAreStoredVerbatimAndServedAsJsonText`; frontend `renders HTML in comments as plain text (§11)` | ✅ API + UI |
| Two people edit the same ticket | second write gets 409 `VERSION_CONFLICT`; UI shows the conflict dialog and reloads | `ConcurrencyIntegrationTest`; frontend `shows the conflict dialog on VERSION_CONFLICT…` | ✅ API + UI |
| Kanban drop that the server wouldn't allow | nothing moves; toast says why (from `deniedActions`) | `maps board moves to the action the server must allow` + browser check | ✅ UI |
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

### AI (Phase 6)

| Case | Expected | Test | Status |
|---|---|---|---|
| Missing key | status `enabled:false`; endpoints 503; core app unaffected | `AiDisabledIntegrationTest` | ✅ |
| Groq timeout / 5xx | graceful 503 `AI_UNAVAILABLE` within the timeout | `AiIntegrationTest.providerErrorsAndTimeoutsDegradeGracefully` | ✅ |
| Repeated failures | circuit opens; no further provider calls | `circuitOpensAfterRepeatedFailuresAndStopsCallingTheProvider` | ✅ |
| Model returns invalid JSON | suggestion discarded (503) | `invalidJsonIsDiscarded` | ✅ |
| Model returns invalid enum | field dropped, rest kept | `invalidEnumValuesAreDroppedButTheRestIsKept` | ✅ |
| Prompt-injection text in a ticket | no effect on system behaviour | `promptInjectionInATicketHasNoEffectOnTheSystem` | ✅ |
| PII in ticket text | redacted before sending | `piiIsRedactedBeforeLeavingTheSystem`, `PiiRedactorTest` (10) | ✅ |
| Feedback on someone else's insight | 404 | `summaryAndFeedback` | ✅ |

### AI teammate (ADR-028)

| Case | Expected | Test | Status |
|---|---|---|---|
| Ticket assigned to the AI | AI starts progress, posts a reply (author marked `ai`), stores a PENDING proposal, notifies the assigner; **ticket not resolved** | `AiTeammateIntegrationTest.aiWorksAnAssignedTicketAndProposesWithoutResolvingIt` | ✅ |
| Lead approves | ticket RESOLVED with the proposed note, authored by the lead | `aLeadApprovesAndTheTicketIsResolvedWithTheProposedNote` | ✅ |
| Lead takes over | assigned to the lead, still IN_PROGRESS, proposal REJECTED | `aLeadCanTakeTheTicketOverInstead` | ✅ |
| Agent tries to decide | 403; ticket unchanged; `canDecide=false` | `anAgentCannotDecideOnAProposal` | ✅ |
| Ticket moved on after the proposal | 409 `AI_PROPOSAL_STALE` | `aProposalGoesStaleWhenTheTicketMovesOn` | ✅ |
| CRITICAL ticket | handed back to the assigner at once, **no model call** | `criticalTicketsGoStraightBackToAHumanWithoutCallingTheModel` | ✅ |
| Low confidence / model asks for a human | handed back, no proposal | `lowConfidenceOrAskingForAHumanHandsTheTicketBack` | ✅ |
| Unusable or prompt-injected model answer | handed back, never resolved | `anUnusableModelAnswerHandsTheTicketBack` | ✅ |
| Sign-in as the AI | 401 `AUTH_INVALID_CREDENTIALS` | `theAiTeammateCanNeverSignIn` | ✅ |
| Promote the AI to lead/admin | 409 `AI_TEAMMATE_FIXED` | `theAiTeammateStaysAPlainAgent` | ✅ |
| Groq rejects the model's JSON (400 `json_validate_failed`) | retried once; the AI still proposes | `aRejectedJsonAnswerIsRetriedOnceAndTheAiStillProposes` | ✅ |
| Tab opened before a deploy loads a lazy page | reloads once into the new version instead of the 500 page (loop-guarded) | `stale-build.test.ts` | ✅ |
