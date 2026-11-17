# Observability

## Simple explanation

When something goes wrong, we need to find out what happened quickly. Every request gets an id
(`X-Request-Id`). It shows up in the response, in every error message the user sees (`traceId`) and
in every log line the backend writes for that request. A user can quote the id, and we can find
exactly the right logs. In Docker, logs are written as JSON so log tools can search them by field.
Metrics and dashboards arrive in Phase 10.

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

### Health (done, Phase 0)

`/actuator/health/liveness` and `/readiness` back the Docker healthchecks.

### Metrics and dashboards (Phase 10)

Micrometer + Prometheus registry, custom counters (`tickets_created_total{source}`,
`webhook_requests_total{outcome}`, ...), Grafana dashboard under the `observability` profile.
