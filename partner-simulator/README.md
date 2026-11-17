# Partner simulator

Plays an **external partner company**: signs ticket webhooks with HMAC-SHA256 using its own copy of
the shared secret, and fires scenarios at Untangle, printing expected vs actual.

It deliberately shares **no code** with the backend (a real partner wouldn't have our code), so it's
an independent check of the contract in `docs/webhook-integration-guide.md`. ADR-019 explains why.

## Run

```bash
make up                       # the stack, with demo data
make simulate                 # all scenarios, in Docker, through nginx
make simulate SCENARIO=burst  # one scenario
make simulate-local           # with local Node 26 instead of Docker
node src/cli.ts --list        # scenario names
```

Each run logs in as the demo admin, creates a **fresh partner** (the admin API returns its secret
once, as it would to a real partner), runs the scenarios, then deactivates the throwaway partners.
Exit code 1 if any scenario fails; CI runs it on every push.

| Scenario | Expected |
|---|---|
| `valid` | 201 |
| `duplicate` | 201 then 200, same ticket |
| `concurrent-duplicate` | 20 parallel → exactly 1×201, 19×200, one ticket |
| `tampered-body` | 401 |
| `wrong-secret` | 401 |
| `expired-timestamp` / `future-timestamp` | 401 |
| `missing-signature` | 401 |
| `unknown-partner` | 401 |
| `invalid-priority` (correctly signed) | 400 |
| `oversized-payload` (70 KB) | 413 |
| `burst` (partner limit 5/min, 10 sent) | 5×201, 5×429 |
| `rotation` (old secret within grace, new secret) | 201, 201 |

Environment: `SIM_BASE_URL` (default `http://localhost:3000`), `SIM_ADMIN_EMAIL`,
`SIM_ADMIN_PASSWORD` (default demo admin).

## Code

| File | What |
|---|---|
| `src/signing.ts` | the signature scheme, the partner's side (about 10 lines) |
| `src/client.ts` | admin setup calls and webhook sending (including deliberate mistakes) |
| `src/scenarios.ts` | the 13 scenarios |
| `src/cli.ts` | argument parsing, setup, results table |

Node 26 runs TypeScript directly (type stripping), so there's no build step and no runtime
dependencies. `npx tsc --noEmit` type-checks it.
