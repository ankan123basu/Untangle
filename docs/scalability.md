# Scalability

## Simple explanation

Untangle runs comfortably on one small server today. This page shows what we built so it can grow
(several backend copies behind a load balancer, a shared cache, fast queries), the load-test
numbers, and what we would do next at 10× and 100× the traffic.

## Technical details

### Built now (§15)

| Concern | What exists | Proof |
|---|---|---|
| Horizontal scaling | stateless API (JWT); no server session | any instance can serve any request |
| Scheduler on many instances | ShedLock: exactly one instance runs the SLA scan each minute | `shedlock` table, `SlaEscalationIntegrationTest` |
| Pagination | every list is paged; `size` > 100 rejected | `TicketQueryIntegrationTest` |
| Indexes match the filters | composite/partial indexes per filter | `EXPLAIN ANALYZE` at 200k tickets in [database.md](database.md#query-plans-section-15-verified-with-explain-analyze) |
| No N+1 queries | fetch joins / entity graphs | `QueryCountIntegrationTest`: list, timeline and workload run 1–3 statements whatever the row count |
| Connection pool | HikariCP sized by `DB_POOL_MAX_SIZE` (default 10) | metrics on the Grafana dashboard |
| Dashboard cache | 30 s TTL, evicted after every committed ticket change; Caffeine, or **Redis** under the `redis` profile so all instances share it | `DashboardCacheIntegrationTest`, `RedisProfileIntegrationTest` |
| Distributed rate limiting | Bucket4j buckets in memory, or in **Redis** (Lettuce, CAS) under the `redis` profile | `RedisProfileIntegrationTest` |
| Frontend | static files from nginx with immutable caching; route-level code splitting | Lighthouse below |

### Load test results (k6, `make load`)

Docker stack on a laptop (Apple Silicon), through nginx, demo data:

| Scenario | Load | Result |
|---|---|---|
| Ticket list with random filters, sorts and searches | 50 req/s for 30 s (1,500 requests) | p95 **5.3 ms**, p99 8.9 ms, **0 % errors** |
| Webhook, steady | 4 signed req/s for 45 s | p95 **21 ms**, all 201 |
| Webhook, burst from one IP | 50 req/s for 10 s | ~350 accepted (bucket of 300 + refill), ~150 × 429, p95 6.5 ms: the limiter protects the service without errors |

Prometheus confirmed the same counts (`webhook_requests_total{outcome}`).

### Stress test: where does one server break? (`make stress`)

**Setup.** A separate copy of the stack (its own database, so real data is never touched) filled
with **200,000 tickets, 600,000 timeline entries and 50 agents** (328 MB). k6 sends a realistic mix
at a fixed rate for 30 s per step: bell 30 %, ticket list with filters/search 25 %, ticket detail
20 %, timeline 10 %, dashboard 8 %, add comment 5 %, create ticket 2 %. Everything (k6, nginx, one
backend limited to 768 MB, Postgres) runs on one Apple Silicon laptop, so the numbers are a lower
bound.

**First run: it broke between 200 and 400 req/s.**

| Rate | p95 | Errors | Postgres CPU |
|---|---|---|---|
| 100/s | 110 ms | 0 % | 192 % |
| 200/s | 304 ms | 0 % | **525 %** |
| 400/s | 26 s | **50 %** | 602 % |

Three problems were found and fixed:

1. **Search did a full table scan.** `q` matches number, subject, customer name and description,
   but only two of those had trigram indexes, so the OR read all 200k rows (about 100 ms on 3 cores)
   on every search. It was the top database cost despite being about 3 % of traffic.
   **Fix:** migration V3 indexes the other two columns, so search uses a BitmapOr (about 8 ms).
2. **Dashboard cache stampede.** Creating a ticket clears the dashboard cache (correct), but then
   every waiting dashboard request recomputed the same counts at once.
   **Fix:** only one request recomputes; the others wait and reuse it (`DashboardCounts`).
   The first attempt, `@Cacheable(sync = true)`, **froze the server at 0 % CPU** at 300 req/s:
   Caffeine computes inside `ConcurrentHashMap.compute`, which holds a monitor during the database
   call and pins Java 21 virtual threads. `-Djdk.tracePinnedThreads=full` proved it. The fix uses a
   `ReentrantLock`, which does not pin (`DashboardCountsStampedeTest`: 20 concurrent virtual threads
   → one computation).
3. **Overload showed as generic 500s** after a 5 s wait for a database connection.
   **Fix:** answered as **503 `SERVICE_BUSY`** with `Retry-After: 2`, so clients back off
   (`OverloadHandlingTest`).

**After the fixes:**

| Rate | Achieved | p50 | p95 | p99 | Errors | Postgres CPU |
|---|---|---|---|---|---|---|
| 100/s | 94/s | 3.6 ms | 63 ms | 159 ms | 0 % | 139 % |
| 200/s | 188/s | 4.3 ms | 96 ms | 289 ms | 0 % | 166 % |
| 300/s | 283/s | 5.5 ms | 127 ms | 282 ms | 0 % | 270 % |
| 400/s | 376/s | 50 ms | 279 ms | 973 ms | **0 %** | 596 % |
| 500/s | 365/s | 1.2 s | 12.8 s | 26.9 s | 1.3 % | 574 % |
| 600/s | 363/s | 3.4 s | 15.4 s | 30.7 s | 20 % | 394 % |

**Verdict.** One backend on a laptop sustains about **375 requests/second with no errors** on
200k tickets. The frontend's polling makes about 0.2 requests/second per signed-in person, so that
is roughly **1,800 people online at once**. Past that, Postgres CPU is the limit (about 600 %).
More backend copies would not help on their own. Next steps, in order of value: a read replica for
lists and the dashboard, a bigger database machine, then load shedding (a concurrency limit that
answers 503 at once instead of queueing).

### Lighthouse (signed-in dashboard, `make lighthouse`)

| | Performance | Accessibility | Best practices | LCP | CLS |
|---|---|---|---|---|---|
| Desktop | 99 | 100 | 100 | 0.8 s | 0 |
| Mobile (simulated slow 4G, 4× CPU) | 89–100 | 100 | 100 | 0.9–3.5 s | 0.005 |

Measured after the frosted-glass refresh with the ambient 3D layer (ADR-025). Mobile varies
between runs with machine load; the 3D layer never loads on mobile.

### How to scale further (documented, not built)

| When | Change | Why |
|---|---|---|
| Dashboard/report reads dominate | **read replica** for dashboard and list queries | keep writes fast on the primary |
| Partner bursts or slow downstream work | **transactional outbox + message queue** (Kafka or RabbitMQ): the webhook stores the event and answers 202; workers create tickets; notifications move to the same queue | absorb bursts instead of rejecting them; retries without losing events |
| Tens of millions of tickets | **partition `tickets` and `webhook_deliveries` by `created_at`** (monthly) | smaller indexes, cheap retention (drop old partitions) |
| Global users | **CDN** for the SPA; API in more regions | lower latency for static assets |
| Free-text search becomes slow | move search to **OpenSearch** fed by the outbox | relevance ranking, typo tolerance |
| Multi-region | one primary region for writes, read replicas elsewhere; partner webhooks routed to the primary | avoids conflicting writes; ShedLock and idempotency keep working |
| Real-time needs | SSE behind a pub/sub fan-out (Redis) | replaces polling (ADR-021) |

The design choices that make these steps easy: idempotency enforced by the database (safe to retry
from a queue), events published after commit (a natural outbox source), stateless instances, and
feature modules that could be split along the same boundaries if a team ever needed to.
