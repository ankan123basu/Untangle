# Untangle

> **Every complaint, untangled.**

Untangle is a customer-support ticket and escalation platform. Every complaint becomes a ticket
that is **prioritised, assigned, tracked through a lifecycle, commented on, escalated when it
breaches its SLA, and never duplicated**. It doesn't matter whether an agent typed it in or a
partner system sent it through an **HMAC-signed webhook**.

| | |
|---|---|
| **Backend** | Java 21 · Spring Boot 4.1 · PostgreSQL 18 · Flyway |
| **Frontend** | React 19 · TypeScript · Vite 8 · Tailwind v4 · "Glass-Brutalism" design |
| **Infra** | Docker Compose · GitHub Actions · nginx |

## Quick start

Requires Docker Desktop.

```bash
make up
```

Then open <http://localhost:3000>. `make help` lists every command. `make simulate` plays a partner
company sending signed webhooks (13 scenarios, pass/fail table).

The stack starts with **demo data** (9 staff, 60 tickets). Every demo account uses the password
`Untangle@2026`, e.g. `admin@untangle.local`. The API is live (Swagger UI: <http://localhost:8080/swagger-ui>); the screens arrive in Phases 7–8.

For local development with hot reload (JDK 21 + Node 26):

```bash
make setup
make backend-run     # terminal 1
make frontend-dev    # terminal 2 → http://localhost:5173
```

## Project status

| Phase | Scope | Status |
|---|---|---|
| 0 | Foundation: repo, scaffolds, Docker, CI, docs skeleton | ✅ |
| 1 | Database schema, entities, demo data | ✅ |
| 2 | Auth, JWT + refresh rotation, RBAC | ✅ |
| 3 | Ticket core, lifecycle, filters, dashboard API | ✅ |
| 4 | HMAC webhook + partner simulator | ✅ |
| 5 | SLA escalation + notifications | ✅ |
| 6 | AI assistance (Groq, optional) | ✅ |
| 7–9 | Frontend foundation, screens, 3D and motion | ⏳ next |
| 10–11 | Hardening, E2E, docs and demo | |

## Documentation

Start at [`docs/README.md`](docs/README.md). Architecture decisions live in
[`docs/decisions/`](docs/decisions/). The full specification is in
[`MASTER_PROMPT.md`](MASTER_PROMPT.md).

## Repository layout

```
backend/            Spring Boot API (package-by-feature under dev.untangle)
frontend/           React SPA + nginx image
partner-simulator/  Node CLI that plays an external partner (Phase 4)
docs/               Design docs, ADRs, runbook, "how it works" notes
docker-compose.yml  postgres · backend · frontend (+ redis profile)
Makefile            every developer command
```
