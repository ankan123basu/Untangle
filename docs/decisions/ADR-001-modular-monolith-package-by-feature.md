# ADR-001: Modular monolith, package-by-feature

- **Status:** Accepted
- **Date:** 2026-11-18
- **Phase:** 0

## Context

Untangle has around ten features (auth, agents, tickets, comments, webhooks, escalation,
notifications, dashboard, AI) that share one database and must enforce identical business rules
for two entry points (REST and webhook). One developer builds it; senior engineers review it.

## Options considered

1. **Microservices** (ticket, webhook, auth, notification services). Independent scaling and
   deploys, but distributed transactions, network failure modes, service discovery and much more
   infrastructure. Idempotent webhook create + ticket insert would need an outbox or saga.
2. **Monolith, package-by-layer** (`controllers/`, `services/`, `repositories/`). Familiar, but a
   feature is scattered across folders and nothing stops any service from calling any repository.
3. **Modular monolith, package-by-feature.** One deployable; each feature owns its controller,
   service, repository, domain and DTOs; boundaries enforced by ArchUnit.

## Decision

Option 3. Root package `dev.untangle`, one sub-package per feature plus `common`, `config` and
`security`. Inside each feature: `controller → service → repository`. Controllers use DTOs only;
entities never leave the service layer.

## Consequences

- **Pros:** one transaction can create a ticket and its SYSTEM comment atomically. Simple to run
  (`make up`) and debug. Features are cohesive and easy to explain. Can be split into services later
  along the same boundaries if one part needs to scale independently.
- **Cons:** everything scales together; one bad deploy affects all features. Discipline is needed
  to keep boundaries, so ArchUnit rules (Phase 1) are mandatory, not optional.
- **Follow-ups:** ArchUnit tests in Phase 1; `scalability.md` describes how we would extract the
  webhook intake behind a queue at 100x load.

## Simple explanation

We build one app, but organise its code like separate rooms, one per feature, with locked doors
between them. It's as easy to run as one app and as tidy as many.
