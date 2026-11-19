# ADR-028: An AI teammate that proposes, and a lead who approves

- **Status:** Accepted (amends ADR-022 "AI optional and suggestion-only")
- **Date:** 2026-11-19
- **Phase:** post-12 (feature requested by the owner)

## Simple explanation

Untangle now has an AI member of the team, **"Untangle AI"**. A lead can assign it a ticket like
any other agent. Within a few seconds it reads the ticket and does three things:
- starts work on the ticket
- posts a reply for the customer on the timeline, marked with an AI badge
- **proposes** a resolution note, with how confident it is

A human lead then makes the decision:
- **Approve & resolve:** the ticket is resolved with the AI's note, in the lead's name.
- **Take over:** the ticket is assigned to the lead, and the proposal is not used.

The AI hands the ticket back, with the reason on the timeline and a notification to whoever gave
it the ticket, when:
- the ticket is CRITICAL
- AI is switched off
- the model asks for a human or is less than 60% confident
- the model's answer is unusable, or the provider is down

It can never sign in, never resolve or close anything by itself, and admins can switch it off by
deactivating it on the Agents page.

## Context

ADR-022 made AI strictly suggestion-only ("never act on model output"). The owner wanted
something more distinctive: an AI that works tickets like a teammate. We offered three levels of
autonomy, and the owner chose the middle one:
- **Assist only:** a suggested reply on the timeline, nothing else.
- **Propose, human approves** (chosen).
- **Auto-resolve low-risk tickets.**

## Decision

1. **Identity.**
   - `agents.kind` is HUMAN or AI. There is exactly one AI agent (unique partial index); it is
     always role AGENT (CHECK constraint).
   - It is created by the application at start-up (`AiAgentAccount`), so fresh databases and test
     resets always have it.
   - Its password hash matches nothing, and login refuses AI accounts before any password check,
     with the same generic 401.
   - Its role cannot be changed (409 `AI_TEAMMATE_FIXED`).
2. **Trigger.**
   - `AiTeammate.onAssigned` listens to `TicketAssigned` after commit and works the ticket on a
     virtual thread (`aiTeammateExecutor`), so the lead's request isn't held up by the model.
   - Only leads and admins can assign to someone else, so only they can hand work to the AI.
3. **Acting.**
   - The AI acts only through `TicketService` with its own AGENT permissions, so every ticket rule
     still applies to it: closed-ticket guard, version check, access policy.
   - It may start progress, comment, and hand back. It never resolves, closes, reassigns, edits or
     changes priority.
   - The model call uses the existing pipeline (redact → `<ticket_data>` fence → timeout and
     circuit breaker → validate). Text is capped, confidence is clamped to 0..1 (missing = 0), and
     an answer without a reply and a note is unusable.
4. **Proposal.**
   - Stored in `ai_proposals` with model, prompt version and confidence; at most one PENDING per
     ticket.
   - It goes stale (`SUPERSEDED`) when the ticket moves on without a decision, for example when
     reassigned or handed back.
5. **Decision.**
   - `POST /tickets/{id}/ai-proposal/approve` and `/take-over`, leads and admins only.
   - They run in one transaction with the ticket's version check. The approval is a normal
     `transition(RESOLVED, note)` by the lead; the timeline records who approved.
6. **Hand back.**
   - `TicketService.handBack`: if the ticket is IN_PROGRESS it goes back to OPEN, then to the
     person who assigned it (or the queue).
   - It is recorded on the timeline (`AI_HANDBACK`) and notified (`AI_HANDBACK`). Proposals ready
     for review notify the assigner (`AI_PROPOSAL`).

## Consequences

- **Pros:**
  - A clear, defensible human-in-the-loop design: the model never changes an outcome.
  - Every action is audited and attributable (AI vs lead).
  - The kill switch is a normal deactivation.
- **Cons:**
  - It costs one model call per assignment.
  - The proposal can go stale if the ticket changes while the model is thinking; the worker
    re-checks just before writing.
- **Not done:**
  - Learning from approvals or rejections. Proposals keep model, prompt version and outcome, so
    approval rates can be measured per prompt version later.
- **Tests:** `AiTeammateIntegrationTest`, 10 tests against a fake Groq server:
  - propose without resolving
  - approve resolves with the note
  - take over
  - an agent can't decide
  - stale proposal → 409
  - CRITICAL handed back without a model call
  - low confidence → hand-back
  - an unusable or injected answer → hand-back
  - the AI can't sign in
  - its role is fixed

  The frontend has `ai-teammate.test.tsx` with 3 tests.

## Addendum: first live run on real Groq (2026-11-19)

**Simple explanation:** in the first live test, some AI calls failed with "Bad Request". The model
(`openai/gpt-oss-20b`) thinks before it answers, and its thinking used up the token budget, so its
JSON answer was cut off and Groq rejected it. We gave it more room, asked it to think briefly, and
now retry once when Groq says the JSON was invalid. Every live scenario then worked.

**Technical details:**

- `max-tokens` went from 800 to 2000.
- `reasoning_effort: low` is sent for gpt-oss models.
- `GroqChatClient` retries once on 400 `json_validate_failed`, and logs the provider's error code
  only, never the content.
- Live results:
  - A human-assigned ticket was started and resolved by the agent.
  - The AI proposed at 95–98% confidence and the lead approved: RESOLVED.
  - The lead took over a proposal: assigned to the lead, still IN_PROGRESS.
  - A CRITICAL ticket was handed back without a model call.
  - The AI handed back a refund ticket on its own, saying the order number was missing.
  - An agent's attempt to approve got 403.

## Addendum: the AI teammate is named "Unty" (2026-11-19)

**Simple explanation:** the AI teammate was first called "Untangle AI". It now has its own
friendly name, **Unty** (from *untie* + Untangle), with the address `unty@untangle.local`.

**Technical details:**

- The V5 migration renames the default row only, so a name an admin has set is kept.
- Notifications, timeline notes and the ticket panel read the name from the agent record (no
  hard-coded name). The `/ai/teammate` endpoint returns it.

