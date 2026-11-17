# How it works — Phase 4: Signed partner webhook

## Simple explanation

Partner companies (say, a bank whose customers complain to it about something we handle) can now
send complaints straight into Untangle:

- Each partner gets a **secret**, shown to our admin exactly once. We store it locked with a separate
  key, so even a copy of our database doesn't reveal it.
- Every request carries a **signature**: a fingerprint of the message, the time and the secret. If
  one byte changes, the time is too old, or the sender doesn't know the secret, the request is
  refused.
- If a partner sends the **same complaint twice** (their system retried), we return the ticket we
  already made. Even 20 copies arriving at the same instant create exactly one ticket.
- Rejected requests get a plain "rejected" (no hints for attackers), but our admins see the **exact
  reason** for every attempt in a log.
- Secrets can be **rotated**: the old one keeps working for 24 hours so the partner can switch
  without losing messages.
- A separate **simulator** program plays the partner and checks all of this against the running
  system with one command: `make simulate`.

## Technical details

### 1. The pipeline (order matters)

```
POST /api/v1/webhooks/partners/tickets
 ├─ RequestIdFilter                         X-Request-Id
 ├─ WebhookBodyLimitFilter                  Content-Length > 64 KB → 413; bounded read; cache raw bytes
 ├─ Spring Security                         path is permitAll: auth is the HMAC, not a JWT
 └─ WebhookController (byte[] body)  →  WebhookService.receive
      0  per-IP token bucket (300/min)              → 429
      2  headers: partner code, event id, numeric ts, "sha256=<64 hex>"  → 401 generic
      3  partner exists and active                  → 401 generic
      4  |now − ts| ≤ 300 s (past AND future)       → 401 generic
      5  HMAC(current secret | previous in grace) constant-time on raw bytes → 401 generic
      6  per-partner token bucket (rate_limit_per_minute)  → 429 + Retry-After
      7  JSON parse + Bean Validation (same rules as manual) → 400 with errors[]
      8  TicketService.createFromWebhook   fast-path lookup → insert in own tx
                                           unique (partner, event) violated → load winner → 200
      9  DeliveryRecorder (REQUIRES_NEW): one row per attempt, every exit path
```

Every rejection goes through `Attempt.reject(...)`, which records the precise reason and returns
the same generic `WEBHOOK_REJECTED` problem. Look at `WebhookService` for the exact code.

### 2. Idempotency under concurrency

`createFromWebhook` is deliberately **not** `@Transactional`. It uses a `TransactionTemplate` for
the insert so that a unique-index violation rolls back only that insert, and the follow-up lookup
runs in a fresh transaction. The fast-path lookup avoids failed inserts for ordinary sequential
retries; correctness comes from `ux_tickets_partner_event` (ADR-012). Proven twice: by
`WebhookIntegrationTest.twentyConcurrentRetriesCreateExactlyOneTicket` (MockMvc, 20 threads) and
by the simulator's `concurrent-duplicate` (20 real HTTP requests through nginx).

### 3. Secrets at rest

`SecretCipher`: AES-256-GCM, a random IV per encryption, AAD = partner code, format
`v1:<iv>:<ct+tag>`. The key comes from `APP_SECRET_ENCRYPTION_KEY` (32 bytes; fail-fast), and
`make .env` generates it. The demo partner `acme-bank` receives `acme-demo-secret-change-me` at
startup (demo profile only), so the curl example in the integration guide works on a fresh clone.

### 4. Admin endpoints

Create a partner (the secret is in the response once), rotate (new secret once; old valid 24 h),
rename or change the rate limit, activate/deactivate, and the paginated delivery log filtered by
partner, outcome and time.

### 5. Verified at the end of Phase 4

| Check | Result |
|---|---|
| `./mvnw verify` | **363 tests** pass (+43) |
| Signing snippets in the guide | OpenSSL, Python, Java and Node all produce the same test-vector signature |
| `make simulate` against `make up` | **13/13** scenarios pass through nginx (incl. 20 concurrent duplicates → 1 ticket, 5×201/5×429 burst, rotation) |
| curl example from the guide (demo partner) | 201, ticket `UNT-000074` created |

### Questions your lead might ask

- **"Why not parse the JSON and sign that?"** Two JSON texts can be "equal" and have different
  bytes. We verify the exact bytes the partner signed, before parsing anything.
- **"Why is a duplicate 200 and not 409?"** Partners usually retry on non-2xx. A 409 would cause
  more retries of something that already succeeded.
- **"Why a per-IP limit before checking the signature?"** HMAC verification and DB lookups cost
  something; unsigned junk shouldn't be able to make us spend that.
- **"What if the encryption key leaks?"** An attacker would also need the database. Rotate the key
  and then every partner secret (runbook). A KMS is the documented next step.
