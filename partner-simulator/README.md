# Partner simulator

A standalone Node.js + TypeScript CLI that plays an **external partner company**. It signs ticket
webhooks with its own copy of the shared HMAC secret and fires scenarios at the Untangle backend
(valid, duplicate, 20 concurrent duplicates, tampered body, wrong secret, expired/future timestamp,
oversized payload, rate-limit burst, secret rotation, ...), printing expected vs actual results.

It deliberately shares **no code** with the backend: a real partner would not have our code either.

**Status:** placeholder. Built in **Phase 4**, runnable with `make simulate` and as the Docker
Compose `simulator` profile. The signing contract lives in `docs/webhook-integration-guide.md`.
