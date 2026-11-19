# AI features

## Simple explanation

Untangle can optionally ask an AI model (via Groq) for help, in three places:

| Feature | Who | Where | What it does |
|---|---|---|---|
| **Smart triage** | anyone creating a ticket | create form, "Suggest with AI" | suggests category, priority, a one-line summary, the customer's mood and why |
| **Thread summary** | a lead picking up an escalated ticket | ticket page | summarises a long timeline into a few sentences, key points and a next step |
| **Resolution draft** | the agent resolving a ticket | ticket page | drafts a resolution note to edit before saving |

| **AI teammate "Unty"** ([ADR-028](decisions/ADR-028-ai-teammate-proposes-human-approves.md)) | leads assign it tickets | assignee picker; panel on the ticket page | reads the ticket, posts a reply on the timeline and **proposes** a resolution; a lead approves (resolves) or takes over. CRITICAL tickets, low confidence or any failure: handed back with the reason |

Rules we never break:

- **A person always decides.** Suggestions are never applied automatically. The AI teammate may
  start work, comment and hand a ticket back, but only a lead can resolve it, by approving.
- **No personal data leaves.** Emails, phone numbers, card and account numbers are replaced with
  `[EMAIL]`, `[PHONE]`, `[CARD]`, `[NUMBER]` before anything is sent. Customer names and emails
  aren't sent at all.
- **The app works 100% without AI.** If AI isn't configured, is slow or is down, the AI buttons
  simply disappear or say "unavailable"; everything else works.

## Technical details

### Configuration

| Env var | Default | Notes |
|---|---|---|
| `AI_ENABLED` | `false` | must be `true` **and** a key set |
| `GROQ_API_KEY` | — | from console.groq.com |
| `GROQ_BASE_URL` | `https://api.groq.com/openai/v1` | OpenAI-compatible Chat Completions |
| `GROQ_MODEL` | `openai/gpt-oss-20b` | a fast instruction model. **Check `GET {base}/models` or console.groq.com/docs/models before deploying**: Groq retires models regularly, and sources disagreed on the current list when this was written (Nov 2026) |

### Pipeline (every call)

```
AI_ENABLED && key?            no → 503 AI_UNAVAILABLE (UI hides the panel via GET /ai/status)
per-user token bucket 20/min  → 429
PiiRedactor                   emails, cards (13–19 digits), Indian mobiles, phones, 9+ digit runs
wrap in <ticket_data>…</ticket_data>, and neutralise any delimiter inside the data
GroqChatClient                temperature 0.2, response_format json_object, max_tokens 2000, reasoning_effort low (gpt-oss), one retry on json_validate_failed
                              connect 3 s / read 8 s timeout, Resilience4j circuit breaker
                              (≥5 calls, 50% failures → open 30 s)
parse                         first {…} block → JSON object, else "unusable" → 503
validate                      enums must match exactly (after trim/uppercase) or the field is
                              DROPPED; texts length-capped; nothing else is read
store                         ticket_ai_insights {kind, content, model, prompt_version, created_by}
respond                       suggestion + insightId (for 👍/👎 feedback)
```

### Prompt-injection defence (§10)

1. Ticket text is **data**: wrapped in `<ticket_data>` tags. The system prompt says to ignore any
   instructions inside the tags.
2. The data can't close the tag early: `</ticket_data>` inside it becomes `[/ticket_data]`.
3. **Output can't act:** we read only known fields with validated values. Extra fields (e.g.
   `"action": "CLOSE_TICKET"`) are ignored, and no code path turns model output into an action.
4. Test `AiIntegrationTest.promptInjectionInATicketHasNoEffectOnTheSystem`: an injection in the
   ticket, plus a model that "obeys" it, leaves the ticket's status and priority unchanged.

### Prompts

Versioned files in `backend/src/main/resources/prompts/` (`triage-v1.txt`, `summary-v1.txt`,
`reply-draft-v1.txt`). Each insight stores `prompt_version`, and `accepted` feedback lets us compare
versions with SQL:

```sql
SELECT prompt_version, count(*) FILTER (WHERE accepted) * 100.0 / NULLIF(count(accepted), 0) AS pct_useful
FROM ticket_ai_insights GROUP BY prompt_version;
```

### Failure modes

| Failure | Behaviour | Test |
|---|---|---|
| No key / disabled | 503 `AI_UNAVAILABLE`, status `enabled:false`, rest of app fine | `AiDisabledIntegrationTest` |
| Provider 5xx | 503 | `providerErrorsAndTimeoutsDegradeGracefully` |
| Slow provider (> timeout) | 503 after ~timeout | same |
| Repeated failures | circuit opens; instant 503 without calling the provider | `circuitOpensAfterRepeatedFailuresAndStopsCallingTheProvider` |
| Non-JSON answer | 503 "unusable answer" | `invalidJsonIsDiscarded` |
| Invalid enum in answer | that field dropped, rest kept | `invalidEnumValuesAreDroppedButTheRestIsKept` |
| PII in ticket | redacted in the outbound request | `piiIsRedactedBeforeLeavingTheSystem`, `PiiRedactorTest` (10) |

### Not built (bonus)

Webhook auto-triage: partner payloads require `category`, so there's nothing to fill in. If that
becomes optional, run triage asynchronously after the webhook ticket is created and attach a
suggestion; never change the partner's priority.

### Open question for the lead (R10)

Is sending **redacted** ticket text to a third-party LLM acceptable for this fintech support tool?
Until answered, AI stays off by default.
