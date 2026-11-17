# ADR-019: Partner simulator in Node.js + TypeScript, with zero runtime dependencies

- **Status:** Accepted
- **Date:** 2026-11-18
- **Phase:** 4

## Context

We need a separate program that behaves like an external partner: it signs requests with its own
copy of the secret and runs negative scenarios (tampering, replay, bursts, rotation). If it reused
backend code, a bug in our signing would be "verified" by the same bug.

## Options considered

1. **Java module next to the backend.** Same language, but the temptation to share classes is high,
   and it would be a second Maven build.
2. **Postman/Newman collection.** No real concurrency; signing scripts are awkward.
3. **Node.js + TypeScript CLI.** Built-in `crypto` HMAC and `fetch`, real parallelism with
   `Promise.all`, the same language as the frontend, and how many partners actually integrate.

## Decision

Option 3. Node 26 runs `.ts` files directly (type stripping), so there's no build step and no
runtime dependencies; `tsc --noEmit` type-checks it in CI. It creates a fresh partner per run through
the admin API (results never depend on leftovers), runs 13 scenarios, prints expected vs actual, and
exits non-zero on failure. It runs locally (`make simulate-local`), in Docker (`make simulate`,
Compose profile `simulator`) and in CI against the real stack.

## Consequences

- **Pros:** an independent implementation of the signing contract; an end-to-end check of HMAC,
  idempotency under 20 concurrent retries, replay window, rate limit and rotation on every CI run.
- **Cons:** needs admin credentials to set up partners (demo defaults; overridable via env).

## Simple explanation

A small separate program pretends to be a partner company and tries everything a real partner (or
an attacker) might do, then prints a pass/fail table.
