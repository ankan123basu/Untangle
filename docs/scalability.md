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

### Lighthouse (signed-in dashboard, `make lighthouse`)

| | Performance | Accessibility | Best practices | LCP | CLS |
|---|---|---|---|---|---|
| Desktop | 100 | 100 | 100 | 0.8 s | 0 |
| Mobile (simulated slow 4G, 4× CPU) | 91 | 100 | 100 | 3.5 s | 0 |

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
