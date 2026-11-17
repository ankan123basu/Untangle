# How it works — Phase 0: Foundation

## Simple explanation

Phase 0 builds the **workshop**, not the product yet. After it:

- `make up` starts three containers: the **database** (PostgreSQL), the **backend** (Spring Boot)
  and the **frontend** (React served by nginx). It waits until each one says it's healthy.
- `make test` / `make ci` run every check the CI server runs, on your laptop.
- Opening <http://localhost:3000> shows the Untangle brand card. It proves React, the design tokens,
  the production build and nginx all work together.
- The docs folder has a page for every topic, plus the first seven decision records (ADRs).

## Technical details

### 1. The request path you can already see working

```
Browser ──:3000──▶ nginx (frontend container, non-root)
                    ├─ /           → index.html + hashed /assets (SPA fallback for deep links)
                    ├─ /healthz    → 200 "ok" (container healthcheck)
                    └─ /api/*      → proxy_pass http://backend:8080   (one origin ⇒ no CORS)
backend container (non-root, JDK 21)
                    └─ /actuator/health/{liveness,readiness}  ← Docker HEALTHCHECK (curl)
postgres container  ← pg_isready healthcheck; published on host :5433
```

Compose start order: `postgres (healthy) → backend (healthy) → frontend (healthy)`, via
`depends_on: condition: service_healthy`. `make up` uses `docker compose up --wait`, so it only
returns when everything is healthy.

### 2. Backend scaffold (`backend/`)

| File | What it does, and why |
|---|---|
| `pom.xml` | Spring Boot **4.1.2** parent (manages compatible versions). Starters: `webmvc` (Boot 4's name for web), `validation`, `actuator`. Plugins: **enforcer** (JDK ≥ 21, Maven ≥ 3.9), **Spotless** (google-java-format; `verify` fails on unformatted code), **JaCoCo** (coverage report). |
| `mvnw`, `.mvn/wrapper` | Maven Wrapper pinned to 3.10.1, so nobody needs Maven installed. |
| `UntangleApplication.java` | `@SpringBootApplication` entry point. |
| `*/package-info.java` | One per feature package. Each documents who uses the feature and which phase builds it. This is the package-by-feature skeleton (ADR-001). |
| `application.yml` | Shared config: virtual threads on, graceful shutdown, health probes on, **no stack traces or messages in error responses** (Section 6.3). Default profile `dev`. |
| `application-{dev,test,prod}.yml` | Only what differs per environment. Docker uses `prod`. |
| `UntangleApplicationTests` | Context loads; liveness/readiness are `UP`; error bodies never contain stack traces. Uses Boot 4's `MockMvcTester` (AssertJ-style MockMvc). |
| `Dockerfile` | Multi-stage. **Build:** JDK image, dependency layer cached separately from source, then `jarmode=tools extract --layers` splits the jar so code changes only rebuild the small top layer. **Runtime:** JRE image, `curl` for the healthcheck only, system user `untangle`, container-aware heap (`MaxRAMPercentage=75`), crash on OOM so Docker restarts it. |

### 3. Frontend scaffold (`frontend/`)

| File | What it does, and why |
|---|---|
| `package.json` | Exact versions (`npm install -E`). Scripts: `dev`, `build` (type check, then Vite build), `lint`, `typecheck`, `format`, `test`. `engines.node` = 26. |
| `vite.config.ts` | React + Tailwind plugins, `@/` alias, dev proxy `/api → :8080` (mirrors nginx), Vitest config (jsdom). |
| `tsconfig.*.json` | Strict mode plus `noUncheckedIndexedAccess` and `exactOptionalPropertyTypes`, which catch more real bugs. |
| `eslint.config.js` | Flat config, `strictTypeChecked`, react-hooks. Project rules: no `any`; `dangerouslySetInnerHTML` banned. |
| `src/styles/tokens.css` | Brand tokens as CSS variables, light and dark (system preference or `data-theme`). |
| `src/styles/globals.css` | `@theme inline` maps tokens into Tailwind utilities (`bg-paper`, `shadow-hard`, `rounded-card`). Base styles: brutalist focus ring, `prefers-reduced-motion` respected globally. |
| `src/components/brand/LogoMark.tsx` | The knot-to-checkmark mark. Accessible: labelled when meaningful, hidden when decorative. |
| `src/app/App.tsx` (+ test) | Phase 0 placeholder card; the tests query by role, the same way screen readers see the page. |
| `nginx.conf` | SPA fallback; immutable caching for `/assets` and `no-cache` for `index.html`; CSP, `X-Frame-Options: DENY`, `nosniff`, `Referrer-Policy`, `Permissions-Policy`; `/api` proxy with SSE-friendly settings. |
| `Dockerfile` | `node:26` builds with `npm ci`; `nginx-unprivileged` (non-root, port 8080) serves the files. |

### 4. Tooling

- **`Makefile`**: one entry point (`make help`). On macOS it sets `JAVA_HOME` to JDK 21, because
  Homebrew's Maven otherwise runs on JDK 27. `make up` creates `.env` from `.env.example` on a fresh clone.
- **`.env.example`**: every variable, including future ones marked with their phase.
- **CI (`.github/workflows/ci.yml`)**: three jobs.
  - `backend`: `mvnw verify` (tests, Spotless, coverage).
  - `frontend`: lint, format check, type check, tests, build, `npm audit`.
  - `docker`: builds the images, waits for the stack to be healthy, then smoke-tests through nginx.
- **Dependabot**: weekly grouped updates for Maven, npm, Docker images and Actions.

### 5. Verified at the end of Phase 0

| Check | Result |
|---|---|
| `./mvnw verify` | 4 tests pass, Spotless clean |
| `npm run lint` / `typecheck` / `test` / `build` | clean / clean / 3 tests pass / 221 kB JS (69 kB gzip) |
| `make up` | postgres, backend and frontend all **healthy** |
| `curl :3000/` headers | CSP, X-Frame-Options DENY, nosniff, Referrer-Policy present |
| Deep link `/tickets/UNT-000001` | 200 (SPA fallback) |
| `/api/*` via nginx | reaches the backend (404 until Phase 2–3 add routes) |
| Container users | backend `untangle` (uid 999), frontend `nginx` (uid 101), both non-root |
| Postgres | 18.7 |

### Questions your lead might ask

- **"Why not `ddl-auto=update`?"** See ADR-003: the schema relies on partial unique indexes and
  CHECK constraints that only migrations express. Hibernate only validates.
- **"Why does the frontend proxy `/api` instead of enabling CORS?"** One origin means the refresh
  cookie can be `SameSite=Strict`, and there's no CORS surface to misconfigure.
- **"Why curl in the backend image?"** Only for the Docker `HEALTHCHECK`. Alternatives (a Java
  health client, or a distroless image with no shell) are possible; curl is the simplest
  auditable option.
