# ADR-023: Exact OpenAPI contract for generated types, and vendored React Bits

- **Status:** Accepted
- **Date:** 2026-11-18
- **Phase:** 7

## Context

The frontend generates its API types from the backend's OpenAPI document (`openapi-typescript`).
springdoc marks almost nothing as `required` and never says which fields can be `null`. Every
generated property was therefore optional (`assignee?: AgentRef`), so TypeScript could not tell
"always present" from "sometimes null" and the UI would need defensive checks everywhere.

Separately, the brief asks for React Bits components. They are distributed as source through the
shadcn CLI registry, not as an npm package, and they don't compile under our strict TypeScript
settings (`noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`, React 19 hook rules).

## Options considered

1. **Hand-written TS types.** Precise, but they drift from the backend silently.
2. **Generated types as-is.** Always in sync, but imprecise (everything optional).
3. **Make the contract precise at the source.** Two springdoc customizers in `OpenApiConfig`:
   every property of a *response* schema is `required`; record components annotated with
   `@jakarta.annotation.Nullable` become `T | null` (or `oneOf [$ref, null]`). Request schemas keep
   their Bean Validation `required` lists.

For React Bits: (a) install via npm (not possible), (b) vendor the source and patch it,
(c) write our own equivalents.

## Decision

- Option 3 for the contract. The backend lists nullable response fields explicitly; the frontend
  regenerates types with `make api-types` (`openapi.json` and `schema.d.ts` are committed, so
  builds never need a running backend).
- React Bits: vendor into `src/components/reactbits/` with the shadcn CLI (pinned 4.23.0), patch
  type errors with `// untangle:` comments, and lint that folder with `recommended` instead of
  `strictTypeChecked`. Components whose patches would amount to a rewrite (Aurora: 42 errors,
  ClickSpark: 29) are not adopted now; Phase 9 decides between adapting them and first-party code.

## Consequences

- **Pros:** `ticket.assignee` is `AgentRef | null`, so the compiler forces a "no assignee" branch;
  the OpenAPI document becomes accurate for every client, not only ours; React Bits visuals with a
  clear provenance marker for every local change.
- **Cons:** forgetting `@Nullable` on a new nullable field makes the contract lie (mitigated: such
  fields are rare, reviewed, and a null in a "required" field would show up in frontend tests);
  vendored code must be re-patched by hand when upgraded.

## Simple explanation

The backend now tells the frontend exactly which fields are always there and which may be empty,
so the frontend can't forget the "empty" case. The fancy animated components are copied into our
project and fixed up to meet our strict rules, with every fix labelled.
