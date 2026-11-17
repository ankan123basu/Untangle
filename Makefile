# Untangle — developer commands. Run `make` or `make help` to list them.
#
# Local toolchain (JDK 21, Node 26) for build/test/lint; Docker for the running stack.
# See docs/runbook.md for details and troubleshooting.

SHELL := /bin/bash
.DEFAULT_GOAL := help

COMPOSE := docker compose
MVNW    := cd backend && ./mvnw -B
NPM     := cd frontend && npm

# On macOS, Homebrew's Maven runs on its own newest JDK. Point every Maven call at JDK 21 so
# local builds match Docker and CI. On Linux/CI, JAVA_HOME from the environment is used.
ifeq ($(shell uname -s),Darwin)
  JDK21 := $(shell /usr/libexec/java_home -v 21 2>/dev/null)
  ifneq ($(JDK21),)
    export JAVA_HOME := $(JDK21)
  endif
endif

.PHONY: help
help: ## Show this help
	@grep -hE '^[a-zA-Z0-9_-]+:.*?## ' $(MAKEFILE_LIST) | \
	  awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[1m%-16s\033[0m %s\n", $$1, $$2}'

# ---------------------------------------------------------------------------
# Setup
# ---------------------------------------------------------------------------
.env:
	@cp .env.example .env
	@echo "Created .env from .env.example (local defaults)."

.PHONY: setup
setup: .env ## Install frontend deps and pre-fetch backend deps
	$(NPM) ci --no-audit --no-fund
	$(MVNW) -q dependency:go-offline

# ---------------------------------------------------------------------------
# Docker stack
# ---------------------------------------------------------------------------
.PHONY: up
up: .env ## Build and start postgres + backend + frontend (waits until healthy)
	$(COMPOSE) up -d --build --wait
	@echo ""
	@echo "  App      http://localhost:$$(grep -E '^FRONTEND_HOST_PORT=' .env | cut -d= -f2 || echo 3000)"
	@echo "  API      http://localhost:$$(grep -E '^BACKEND_HOST_PORT=' .env | cut -d= -f2 || echo 8080)/actuator/health"
	@echo "  Postgres localhost:$$(grep -E '^POSTGRES_HOST_PORT=' .env | cut -d= -f2 || echo 5433)"

.PHONY: down
down: ## Stop the stack (keeps the database volume)
	$(COMPOSE) --profile '*' down

.PHONY: nuke
nuke: ## Stop the stack AND delete the database volume (asks first)
	@read -p "Delete all local Untangle data? [y/N] " ans; [ "$$ans" = "y" ] && $(COMPOSE) --profile '*' down -v || echo "Cancelled."

.PHONY: logs
logs: ## Follow logs of all services
	$(COMPOSE) logs -f --tail=200

.PHONY: ps
ps: ## Show service status and health
	$(COMPOSE) ps

.PHONY: db-shell
db-shell: ## Open psql inside the postgres container
	$(COMPOSE) exec postgres sh -c 'psql -U "$$POSTGRES_USER" -d "$$POSTGRES_DB"'

# ---------------------------------------------------------------------------
# Local development (outside Docker)
# ---------------------------------------------------------------------------
.PHONY: backend-run
backend-run: ## Run the backend locally on :8080 (dev profile)
	$(MVNW) spring-boot:run

.PHONY: frontend-dev
frontend-dev: ## Run the Vite dev server on :5173 (proxies /api to :8080)
	$(NPM) run dev

# ---------------------------------------------------------------------------
# Quality gates
# ---------------------------------------------------------------------------
.PHONY: test
test: test-backend test-frontend ## Run all tests

.PHONY: test-backend
test-backend: ## Backend tests + coverage report (backend/target/site/jacoco)
	$(MVNW) verify -Dspotless.check.skip=true

.PHONY: test-frontend
test-frontend: ## Frontend unit/component tests (Vitest)
	$(NPM) test

.PHONY: lint
lint: ## Check formatting and lint rules (backend + frontend)
	$(MVNW) -q spotless:check
	$(NPM) run -s lint
	$(NPM) run -s format:check

.PHONY: typecheck
typecheck: ## TypeScript strict type check
	$(NPM) run -s typecheck

.PHONY: fmt
fmt: ## Auto-format all code
	$(MVNW) -q spotless:apply
	$(NPM) run -s format

.PHONY: build
build: ## Build backend jar and frontend bundle
	$(MVNW) -q package -DskipTests
	$(NPM) run -s build

.PHONY: ci
ci: lint typecheck test build ## Run exactly what CI runs (minus Docker)

# ---------------------------------------------------------------------------
# Later phases (placeholders so the command surface is stable)
# ---------------------------------------------------------------------------
.PHONY: seed
seed: .env ## Load demo data into the Docker database (restarts backend with the demo profile)
	SPRING_PROFILES_ACTIVE=prod,demo $(COMPOSE) up -d --wait backend
	@echo "Demo data loaded. Log in as admin@untangle.local / Untangle@2026 (Phase 2+)."

.PHONY: simulate
simulate: ## (Phase 4) Run partner webhook scenarios
	@echo "Partner simulator arrives in Phase 4."

.PHONY: clean
clean: ## Remove build outputs
	$(MVNW) -q clean
	rm -rf frontend/dist frontend/coverage
