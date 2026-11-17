# ADR-022: AI is optional, suggestion-only, and never trusted

- **Status:** Accepted
- **Date:** 2026-11-18
- **Phase:** 6

## Context

LLMs can speed up triage and resolution, but they hallucinate, can be manipulated by text inside a
ticket (prompt injection), are an external dependency that can be slow or down, and receive data we
send them, which in fintech may include PII.

## Options considered

1. **Auto-apply AI decisions** (e.g. set priority on create). Fast, but wrong answers and injections
   become real changes.
2. **Suggestions the user accepts per field, with strict output validation.**
3. **No AI.** Safe, but forgoes real productivity gains.

## Decision

Option 2, off by default:
- Every helper returns suggestions; the UI shows accept/ignore chips and editable drafts. No server
  code path applies model output.
- Output is parsed as a JSON object; only known fields are read; enums must match exactly; texts are
  capped. Anything unusable → `503 AI_UNAVAILABLE`, never a guess.
- Input is PII-redacted, wrapped in delimiters, and the delimiters are neutralised inside the data.
- Resilience: timeouts, a Resilience4j circuit breaker, per-user rate limit. `GET /ai/status` lets
  the UI hide AI when it's off.
- Provenance and feedback (`model`, `prompt_version`, `accepted`) are stored per insight.

## Consequences

- **Pros:** a model mistake costs one ignored suggestion; injections can't act; the core app is
  independent of the provider; usefulness is measurable.
- **Cons:** an extra click to accept suggestions; redaction is heuristic (hence default-off and
  question R10).

## Simple explanation

The AI is an assistant that whispers suggestions. It can't press any buttons, it never sees
customers' contact details, and if it's unavailable the app carries on as normal.
