# Tech stack and pinned versions

## Simple explanation

Every library is pinned to an exact version, so the project builds the same way on every machine
and in CI, today and in a year. Versions were checked against Maven Central, npm and Docker Hub on
**2026-11-18**. Dependabot opens weekly PRs to move them forward deliberately.

Libraries marked *(Phase N)* are not installed yet. They are added, at the version checked then,
in the phase that first uses them.

## Technical details

### Backend

| Component | Version | Notes |
|---|---|---|
| Java | **21 LTS** (Temurin 21.0.13 in Docker; local OpenJDK 21.0.13) | `maven-enforcer` requires ≥ 21; bytecode targets 21 |
| Spring Boot | **4.1.2** | Parent POM manages Spring, Hibernate, Jackson, JUnit, Testcontainers, Flyway, PostgreSQL driver |
| Spring Framework / Security | managed by Boot (Security 7.1.2) | |
| Hibernate ORM | managed (7.4.x) | |
| Jackson | managed (3.1.x) | Boot 4 moved to Jackson 3 (`tools.jackson` packages) |
| JUnit Jupiter | managed (6.0.x) | |
| Maven / Wrapper | 3.10.1 via `./mvnw` | |
| Spotless (google-java-format) | 3.11.0 | |
| JaCoCo | 0.8.16 | |
| Flyway (+ `flyway-database-postgresql`) | managed (12.4.x) | Boot-managed, not the newest 13.x, to stay compatible |
| PostgreSQL JDBC | managed (42.7.14) | |
| Testcontainers (`testcontainers-postgresql`, `-junit-jupiter`) | managed (2.0.5) | |
| ArchUnit (`archunit-junit5`) | 1.5.1 | |
| Spring Security OAuth2 Resource Server (Nimbus JOSE) | managed (Security 7.1.2) | via `spring-boot-starter-security-oauth2-resource-server` (the old `-oauth2-resource-server` name is deprecated in Boot 4). Chosen over jjwt: ADR-013 |
| springdoc-openapi | 3.2.0 *(Phase 3)* | 3.x line targets Boot 4 |
| MapStruct | 1.6.3 *(Phase 3)* | latest GA (1.7 is still beta). Phase 2 views are tiny records with `of(entity)` factories |
| Lombok (annotation processor path) | managed (1.18.48) | |
| ShedLock | 7.11.0 *(Phase 5)* | |
| Bucket4j (`bucket4j_jdk17-core`) | 8.22.0 | login rate limits; webhook/AI later |
| Caffeine (+ `spring-boot-starter-cache`) | managed (3.2.4) | principal cache, rate-limit buckets |
| Resilience4j (`resilience4j-spring-boot4`) | 2.4.0 *(Phase 6)* | |
| ~~logstash-logback-encoder~~ | not used | Spring Boot 4 has built-in structured JSON logging (`logging.structured.format.console: logstash`), one dependency fewer | |

### Frontend

| Component | Version | Notes |
|---|---|---|
| Node.js | **26** (`.nvmrc`; Docker `node:26.13.1-alpine3.24`) | |
| React / React DOM | 19.3.1 | |
| Vite | 8.3.8 | Rolldown-based |
| @vitejs/plugin-react | 6.3.0 | |
| TypeScript | **6.0.3** | Not 7.x: typescript-eslint 8.73 supports `<6.1` ([ADR-006](decisions/ADR-006-frontend-toolchain.md)) |
| Tailwind CSS (+ `@tailwindcss/vite`) | 4.3.5 | CSS-first config with `@theme` |
| ESLint / typescript-eslint | 10.15.0 / 8.73.0 | flat config, `strictTypeChecked` |
| Prettier (+ tailwind plugin) | 3.10.1 (+ 0.8.1) | |
| Vitest / jsdom | 5.1.0 / 31.0.1 | |
| Testing Library (react / jest-dom / user-event) | 16.3.3 / 7.0.1 / 14.6.12 | |
| three / @react-three/fiber / drei | 0.187.0 / 9.8.2 / 10.7.9 *(Phase 9)* | |
| motion | 14.14.0 *(Phase 7)* | |
| @tanstack/react-query | 5.108.0 *(Phase 7)* | |
| zustand | 5.1.0 *(Phase 7)* | |
| react-router | 8.5.1 *(Phase 7)* | |
| react-hook-form / zod | 7.92.0 / 4.6.7 *(Phase 8)* | |
| recharts / sonner / cmdk | 3.11.0 / 2.0.8 / 1.1.1 *(Phase 8)* | |
| msw / @playwright/test | 3.0.6 / 1.65.1 *(Phases 7 / 10)* | |
| axios, openapi-typescript | pinned when installed *(Phase 7)* | |
| shadcn CLI (React Bits registry) | 4.23.0 *(Phase 7)* | |

### Infrastructure

| Component | Version |
|---|---|
| PostgreSQL | **18.7** (`postgres:18.7-alpine3.24`) |
| Redis (optional) | 8.10.3 (`redis:8.10.3-alpine`) |
| nginx (unprivileged) | 1.31.7 (`nginxinc/nginx-unprivileged:1.31.7-alpine3.24`) |
| Backend runtime image | `eclipse-temurin:21.0.13_9-jre-noble` |
| Docker Engine / Compose (dev machine) | 29.9.3 / v5.7.0 |
| GitHub Actions | checkout v7, setup-java v6, setup-node v7, setup-buildx v4, upload-artifact v7 |
