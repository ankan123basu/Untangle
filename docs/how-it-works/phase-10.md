# How it works — Phase 10: Quality hardening (in progress)

## Simple explanation

Untangle now proves itself automatically: browser tests click through the real app, the build
fails if test coverage drops, the backend reports business numbers to a Prometheus/Grafana
dashboard, and load tests show how it behaves under pressure.

## Technical details

| Area | What | Where |
|---|---|---|
| E2E | Playwright (9 specs): ticket lifecycle, RBAC (UI + API), signed webhook → ticket visible + delivery log, security headers | `frontend/e2e`, `make e2e`, CI docker job |
| Coverage gate | JaCoCo: every `application`/`domain` package ≥ 80% lines; bundle ≥ 80% lines, ≥ 65% branches | `backend/pom.xml` |
| Metrics | `ticket_creations_total{source}` (Prometheus reserves `_created`, so not `tickets_created_total`), `ticket_transitions_total{from,to}`, `sla_breaches_total{level}`, `ticket_escalations_total{mode}`, `webhook_requests_total{outcome}`, `webhook_processing_seconds`, `ai_requests_total{feature,outcome}`, `ai_latency_seconds` | `TicketMetrics`, `DeliveryRecorder`, `WebhookService`, `AiAssistant` |
| Scrape safety | `prod`: actuator on port 8081 (never published); `/livez` `/readyz` stay on 8080 | `application-prod.yml`, Dockerfile, CI check |
| Observability profile | Prometheus v3.15.1 + Grafana 13.3.1 with a provisioned "Untangle overview" dashboard (localhost only) | `infra/`, `docker compose --profile observability up -d` |
| Dashboard cache | 30 s TTL, team counts shared + per-agent "my open"; evicted immediately (`invalidate`) on every committed ticket change; Caffeine or Redis | `DashboardCounts`, `ScalingConfig` |
| Redis profile | shared dashboard cache + Bucket4j/Lettuce rate-limit buckets; auto-config excluded otherwise | `application-redis.yml`, `RedisProfileIntegrationTest` |
| N+1 guard | Hibernate statistics: list, timeline and workload run 1–3 statements regardless of rows | `QueryCountIntegrationTest` |
| Load | k6: list 50 req/s p95 5.3 ms, 0% errors; webhook steady p95 21 ms all 201; 50 req/s burst from one IP → clean 429s, p95 6.5 ms | `load/k6`, `make load` |
| Dependencies | Dependabot (+ partner-simulator), `npm audit` in CI | `.github/dependabot.yml` |

Bugs found: Redis `Cache.clear()` is asynchronous, so a request right after a change could see old
numbers; eviction now uses `beforeInvocation = true` (immediate `invalidate()`).

Verified: SLA scheduler runs in Docker (ShedLock row + "SLA scan escalated" log).

Still to do: scalability.md / observability.md / security.md write-ups and an ADR for the
management port; Phase 11 (final docs, README screenshots, demo script).
