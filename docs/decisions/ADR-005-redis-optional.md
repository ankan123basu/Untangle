# ADR-005: Redis is optional

- **Status:** Accepted
- **Date:** 2026-11-18
- **Phase:** 0 (used from Phase 3/4)

## Context

Two features benefit from shared state across backend instances: the dashboard summary cache
(30 s TTL) and rate limiting (login, webhook, AI). A single-instance demo doesn't need them shared,
and every extra required service makes `make up` heavier and adds a failure mode.

## Options considered

1. **Redis required.** Simplest code path, one implementation, but a mandatory extra container.
2. **No Redis.** In-memory only. Correct for one instance; wrong behind a load balancer (each
   instance has its own counters and cache).
3. **Optional Redis behind interfaces.** In-memory by default; a `redis` Compose profile plus
   `REDIS_ENABLED=true` swaps in Redis-backed implementations.

## Decision

Option 3. The cache uses Spring Cache abstraction (Caffeine in memory, Redis when enabled).
Rate limiting uses Bucket4j with an in-memory or Redis `ProxyManager` chosen by configuration.

## Consequences

- **Pros:** `make up` stays lean; the horizontal-scaling path is real and tested, not only described.
- **Cons:** two implementations to configure and test. Without Redis, limits are per instance
  (documented in `scalability.md`).

## Simple explanation

Redis is a fast shared memory for when we run several copies of the backend. We don't need it for a
single copy, so it's switched off by default and switched on with one setting.
