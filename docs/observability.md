# Observability

## Simple explanation

When something goes wrong, we need to find out what happened quickly. Every request gets an id
(`X-Request-Id`). It shows up in the response, in every error message the user sees (`traceId`) and
in every log line the backend writes for that request. A user can quote the id, and we can find
exactly the right logs. In Docker, logs are written as JSON so log tools can search them by field.
Numbers about the business (tickets created, webhooks rejected, SLA breaches) go to Prometheus and
show on a ready-made Grafana dashboard.

## Technical details

### Correlation id (done, Phase 2)

```
Browser ──X-Request-Id?──▶ nginx ──(keeps it, or generates $request_id)──▶ backend
backend RequestIdFilter: validate [A-Za-z0-9._-]{8,64} → else new UUID
        → MDC["requestId"] → every log line
        → response header X-Request-Id
        → Problem Details "traceId"
```

The filter runs before Spring Security, so even 401s are traceable. Untrusted ids are validated so
they can't inject fake log lines (tested: `ErrorHandlingIntegrationTest.unsafeRequestIdsAreReplaced`).

### Logs (done, Phase 2)

| Profile | Format | Notes |
|---|---|---|
| `dev` | readable text, `LEVEL [requestId]` | `dev.untangle` at DEBUG |
| `prod` (Docker) | **JSON, Logstash format** via Spring Boot's built-in `logging.structured.format.console: logstash` | MDC fields (`requestId`) included; no extra library needed |
| `test` | WARN, app INFO | |

Example:

```json
{"@timestamp":"2026-11-18T19:29:57.48Z","message":"Login succeeded agentId=f23a… role=ADMIN",
 "logger_name":"dev.untangle.auth.application.AuthService","level":"INFO","requestId":"demo-trace-12345"}
```

Level discipline:
- **INFO**: business and security events (login succeeded, agent created or deactivated, logout).
- **WARN**: suspicious or rejected events (wrong password, refresh token reuse, rejected webhooks).
- **ERROR**: unexpected failures only.

Redaction: emails are masked, and tokens, passwords and secrets are never logged.

```bash
docker compose logs backend --no-log-prefix | grep '"requestId":"<id>"'
```

### Health

`/livez` and `/readyz` on the API port (8080) back the Docker healthchecks and CI. In the `prod`
profile all other actuator endpoints live on the management port **8081**, which Compose never
publishes.

### Scheduled jobs (Phase 5)

| Job | Schedule | Lock (ShedLock) | Log line |
|---|---|---|---|
| SLA escalation | every minute (`app.escalation.cron`) | `sla-escalation`, at most 5 min, at least 20 s | `SLA scan escalated N ticket(s)` (only when N > 0) |
| Webhook delivery retention | 03:15 daily | `webhook-delivery-retention` | `Webhook delivery retention: deleted N row(s)` |

Who holds a lock right now: `SELECT * FROM shedlock;`

### Metrics and dashboards (Phase 10)

Micrometer with the Prometheus registry; every series carries `application="untangle"`.

| Metric | Labels | Recorded in |
|---|---|---|
| `ticket_creations_total` | `source` (MANUAL, WEBHOOK) | `TicketMetrics`, after commit |
| `ticket_transitions_total` | `from`, `to` | `TicketMetrics` (reopen counted as CLOSED → OPEN) |
| `sla_breaches_total` | `level` | `TicketMetrics` (automatic escalations) |
| `ticket_escalations_total` | `mode` (automatic, manual) | `TicketMetrics` |
| `webhook_requests_total` | `outcome` (CREATED, DUPLICATE, REJECTED_*, RATE_LIMITED) | `DeliveryRecorder` (every pipeline exit) |
| `webhook_processing_seconds` | histogram | `WebhookService` |
| `ai_requests_total` | `feature`, `outcome` (success, invalid, unavailable) | `AiAssistant` |
| `ai_latency_seconds` | `feature`, histogram | `AiAssistant` |
| `http_server_requests_seconds` | Spring defaults, histogram | Spring Boot |

`tickets_created_total` from the brief can't be used: the Prometheus client reserves the
`_created` suffix, so the counter is `ticket_creations_total`.

```bash
docker compose --profile observability up -d
# Prometheus http://localhost:9090   Grafana http://localhost:3001 (admin / GRAFANA_ADMIN_PASSWORD)
```

The provisioned **Untangle overview** dashboard (`infra/grafana/dashboards/untangle.json`) shows
tickets created and transitions, SLA breaches, webhook outcomes and p95 latency, AI outcomes and
latency, HTTP status rates and slowest endpoints, JVM heap and DB connections. Both tools bind to
localhost only.
