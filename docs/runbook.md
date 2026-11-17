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

### Database and demo data

- On startup, **Flyway** applies pending migrations (`backend/src/main/resources/db/migration`), then
  Hibernate **validates** that entities match the schema. A mismatch stops the app (by design).
- `SPRING_PROFILES_ACTIVE=prod,demo` (the default in `.env.example`) also loads the demo data:
  9 staff, 1 partner, 60 tickets. **Every demo account's password is `Untangle@2026`**, e.g.
  `admin@untangle.local`, `leela.thomas@untangle.local` (lead), `riya.sen@untangle.local` (agent).
- `make seed` loads the demo data into an already-running stack.
- For an empty database: remove `demo` from `SPRING_PROFILES_ACTIVE` and run `make nuke`, then `make up`.
- Local `make backend-run` uses the `dev` profile, which includes `demo`, and connects to the Docker
  Postgres on `localhost:5433`. Start it with `docker compose up -d postgres` first.

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
| Backend exits with `Schema validation: wrong column type ...` | an entity and the SQL schema disagree | fix the entity or add a migration; never switch `ddl-auto` away from `validate` |
| `make test` fails with `Could not find a valid Docker environment` | Testcontainers needs Docker | start Docker Desktop |
| `Validate failed: Migrations have failed validation` | an applied migration file was edited | never edit applied migrations; add `V2__...`. Locally: `make nuke` |

### Signing in

- With demo data: `admin@untangle.local` / `Untangle@2026` (also leads and agents, see above).
- Without demo data: set `BOOTSTRAP_ADMIN_EMAIL` and `BOOTSTRAP_ADMIN_PASSWORD` (≥ 10 chars) in
  `.env`, then `make up`. The first admin is created only if no active admin exists; it never
  modifies existing accounts.
- **Locked out after 5 wrong passwords?** Wait 15 minutes, or clear the lock:
  `make db-shell`, then `UPDATE agents SET locked_until = NULL, failed_login_attempts = 0 WHERE lower(email) = lower('x@y');`

### Secret rotation

**JWT signing key (`JWT_SECRET`):**
1. `openssl rand -base64 48` → put the value in `.env` (or your secret store).
2. Restart the backend (`docker compose up -d backend`).
3. Effect: every access token becomes invalid immediately. Refresh tokens are opaque DB rows, so
   they keep working: browsers silently refresh and users stay signed in. To force everyone to sign
   in again, also run `UPDATE refresh_tokens SET revoked_at = now() WHERE revoked_at IS NULL;`.

**Partner HMAC secret:**
1. Admin → Partners → *Rotate secret* (or `POST /api/v1/admin/partners/{id}/rotate-secret`).
2. Hand the new secret to the partner over a secure channel; it is shown only once.
3. The old secret keeps working for 24 h (`app.webhook.rotation-grace`). Check the delivery log
   until the partner's requests verify with the new one.

**Encryption key (`APP_SECRET_ENCRYPTION_KEY`):** there's no in-place re-encryption tool yet. If the
key is lost or must change: set a new key, restart, then rotate every partner's secret (their old
ciphertexts no longer decrypt, so their webhooks fail until rotated).

### Auth troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Backend exits with `JWT_SECRET is not set` / `too short` | missing or weak key | `make .env` on a fresh clone, or set a ≥ 32-byte base64 value |
| `docker compose` says `JWT_SECRET is empty` | `.env` created before Phase 2 | add `JWT_SECRET=$(openssl rand -base64 48)` to `.env` |
| Every call returns `401 AUTH_TOKEN_EXPIRED` | access token older than 15 min | call `POST /api/v1/auth/refresh` (the SPA does this automatically) |
| Refresh always 401 in Safari over plain HTTP | `Secure` cookie dropped | `AUTH_COOKIE_SECURE=false` (dev only) |
| Backend exits with `APP_SECRET_ENCRYPTION_KEY is not set` | missing key | `make .env` on a fresh clone; for an older `.env` add `APP_SECRET_ENCRYPTION_KEY=$(openssl rand -base64 32)` |
| Partner gets 401 | wrong secret, clock skew, re-serialised body, inactive partner | Admin → Webhook logs shows the exact reason per attempt |
| `make simulate` fails at "Admin login failed" | stack not up or demo data missing | `make up`; `SPRING_PROFILES_ACTIVE` must include `demo`, or set `SIM_ADMIN_EMAIL/PASSWORD` |
| Find the logs for an error | | take `traceId` from the error body: `docker compose logs backend --no-log-prefix \| grep '<traceId>'` |
