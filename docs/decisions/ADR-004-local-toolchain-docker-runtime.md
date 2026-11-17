# ADR-004: Local toolchain for development, Docker for runtime and CI

- **Status:** Accepted
- **Date:** 2026-11-18
- **Phase:** 0

## Context

The developer has JDK 21, Maven, Node 26 and Docker installed, plus a Homebrew PostgreSQL 18
listening on port 5432. Reviewers should be able to run the whole system with only Docker.

## Options considered

1. **Docker-only.** Every build and test runs inside containers. Fully reproducible, but slow
   feedback, awkward IDE integration and debugging.
2. **Local-only.** Fast, but "works on my machine" risk, and reviewers must install everything.
3. **Hybrid.** Local toolchain for the edit-test loop (`make test`, `make lint`, `make backend-run`,
   `make frontend-dev`); Docker Compose for the running system (`make up`) and for the CI Docker job.

## Decision

Option 3.
- Versions are pinned everywhere: `.nvmrc`, `engines`, Maven Wrapper 3.10.1, `maven-enforcer`, and
  exact Docker image tags.
- Docker Postgres publishes on host port **5433** (configurable) so it never clashes with a local
  Postgres on 5432.
- CI runs the same commands as `make ci`, plus a job that builds the images and waits for the stack
  to become healthy.

## Consequences

- **Pros:** fast feedback and normal IDE debugging; reviewers need only Docker; CI proves the images
  work.
- **Cons:** two ways to run things, so the runbook documents both. Local Node/JDK versions can
  drift; the pins plus enforcer catch most of that.

## Simple explanation

While coding we run tools directly on the laptop because it's faster. To run the whole product, or
to check it in CI, we use Docker so it behaves the same everywhere.
