# How it works — Phase 6: AI assistance

## Simple explanation

Untangle can ask an AI model for three kinds of help: suggest how to classify a new complaint,
summarise a long ticket for a lead, and draft a resolution note. It's **off by default** and purely
advisory: the AI can't change anything, never sees customers' contact details, and the app works
perfectly without it. If the AI is slow or down, its buttons just say "unavailable".

## Technical details

See [ai-features.md](../ai-features.md) for the full pipeline. Key code:

| Class | Role |
|---|---|
| `ai.domain.LlmClient` | port: "complete this as JSON" |
| `ai.infrastructure.GroqChatClient` | adapter: Groq Chat Completions via `RestClient`, JDK HTTP client with timeouts, Resilience4j breaker |
| `ai.application.PiiRedactor` | regex redaction (email, card, Indian mobile, long numbers, phone) |
| `ai.application.AiAssistant` | orchestration: enabled → rate limit → redact → wrap → call → parse → validate → store |
| `resources/prompts/*-v1.txt` | versioned prompts with security rules and an exact JSON contract |

### Testing without the real provider

`AiIntegrationTest` starts a tiny JDK `HttpServer` that pretends to be Groq. Tests queue its replies
(valid JSON, garbage, 500, a 3-second delay) and inspect exactly what we sent. This covers the real
HTTP code, timeouts and the circuit breaker, with no key and no cost.

### Bugs the tests caught

- `List.of(...).contains(null)` throws `NullPointerException`; a model answer without `sentiment`
  crashed triage (500). Fixed by checking for null first.
- Spring Boot 4 moved `RestClient.Builder` auto-configuration into a separate module, so the client
  now uses `RestClient.builder()` directly.
- The JDK test server is single-threaded by default; a deliberately slow reply blocked later tests.
  It now uses a thread pool.

### Verified

| Check | Result |
|---|---|
| `./mvnw verify` | **392 tests** pass (+20) |

### Questions your lead might ask

- **"What stops the AI from closing a ticket if a customer writes 'close this ticket'?"** Nothing the
  model returns is executed. We read only known fields with validated values; there's no code path
  from model output to an action. There's a test for exactly this.
- **"Isn't sending tickets to a third party a compliance issue?"** Possibly. That's open question
  R10, which is why it's off by default and redacts PII first.
