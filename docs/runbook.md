# Runbook

## Simple explanation

How to run Untangle on your machine, what each setting means, and what to do when something breaks.

## Technical details

### Prerequisites

| Tool | Version | Needed for |
|---|---|---|
| Docker Desktop | recent | `make up` (the only hard requirement to *run* the app) |
| JDK | 21 | backend build/tests outside Docker |
| Node.js | 26 (`.nvmrc`) | frontend dev/tests outside Docker |
| make, git | any | commands |

### Quick start

```bash
make up        # first run creates .env from .env.example, builds images, waits until healthy
```

- App: <http://localhost:3000>
- Backend health: <http://localhost:8080/actuator/health>
- Postgres: `localhost:5433`, db/user `untangle` (password in `.env`). For pgAdmin, register a server
  with these values.

### Day-to-day development (faster than rebuilding images)

```bash
make setup          # once: npm ci + Maven dependency download
make backend-run    # Spring Boot on :8080 (dev profile)
make frontend-dev   # Vite on :5173, proxies /api to :8080
make test           # all tests
make fmt            # auto-format everything
make ci             # what CI runs
```

### Environment variables

All are documented in [`.env.example`](../.env.example). Never commit `.env`.

### Common failures

| Symptom | Cause | Fix |
|---|---|---|
| `Bind for 0.0.0.0:5432 failed` | another Postgres on the host port | we default to **5433**; change `POSTGRES_HOST_PORT` in `.env` |
| `port 3000/8080 is already allocated` | another app on that port | change `FRONTEND_HOST_PORT` / `BACKEND_HOST_PORT` |
| `Cannot connect to the Docker daemon` | Docker Desktop not running | start Docker Desktop, then `make up` |
| Maven error `Untangle needs JDK 21 or newer` / odd compiler errors | Maven picked another JDK | the Makefile sets `JAVA_HOME` to JDK 21 on macOS; outside make: ``export JAVA_HOME=$(/usr/libexec/java_home -v 21)`` |
| `make up` times out waiting for `backend` | backend failed to start | `docker compose logs backend` |
| Postgres container restarts after changing major version | old volume from another major | `make nuke` (deletes local data) |

### Secret rotation

Partner secret rotation (Phase 4) and JWT key rotation (Phase 2) procedures are added here in those phases.
