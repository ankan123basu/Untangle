# ADR-014: RBAC through permissions mapped from roles, enforced on the server

- **Status:** Accepted
- **Date:** 2026-11-18
- **Phase:** 2

## Context

Three roles (AGENT, LEAD, ADMIN) with a capability table (MASTER_PROMPT §6.2). Some rules are
ownership-based ("agents may transition tickets assigned to *them*"). Rules must be enforced on the
server, and the UI must be able to hide what a user can't do.

## Options considered

1. **Check role names in code** (`if role == LEAD || role == ADMIN`). Simple, but rules scatter
   across the codebase and every change means a hunt.
2. **Permissions in the JWT.** The UI could read them, but they go stale until token expiry, and the
   token gets bigger.
3. **A permission enum + one role→permission table on the server; permissions derived per request
   from the current role.** Ownership is checked separately by a policy object.

## Decision

Option 3.
- `Permission` enum and `RolePermissions`, the single source of truth. Roles are cumulative.
- Authorities = permission names (+ `ROLE_x`).
- Enforcement in two layers: coarse URL rules in `SecurityConfig` (they run *before* request bodies
  are parsed, so unauthorised callers get 403, not a 400 that reveals fields), and
  `@PreAuthorize("hasAuthority('...')")` per controller method.
- Ownership rules (`*_OWN` permissions) go in `TicketAccessPolicy` (Phase 3), which also computes
  `allowedActions`.
- `GET /auth/me` returns the permission list, so the UI can hide or disable actions without
  duplicating the rules.

## Consequences

- **Pros:** one table to read, review and test (`RolePermissionsTest` transcribes §6.2 cell by
  cell; `EndpointAuthorizationMatrixTest` covers role × endpoint over HTTP); role changes apply
  within the cache TTL; configurable rules (agent may close own resolved ticket) are one flag.
- **Cons:** two layers to keep consistent for admin URLs (tested); permission names become part of
  the API contract.

## Simple explanation

Each role comes with a fixed list of things it's allowed to do, kept in one place on the server.
Every request is checked against that list, and the app shows only the buttons on your list.
