# ADR-006: Frontend toolchain: Vite 8, TypeScript 6.0, Tailwind v4, ESLint flat config

- **Status:** Accepted
- **Date:** 2026-11-18
- **Phase:** 0

## Context

The brief asks for React 19 + strict TypeScript + Vite + Tailwind, all at the latest stable
versions. On 2026-11-18, npm `latest` for TypeScript is **7.0.2** (the native Go compiler), but
typescript-eslint 8.73 declares `typescript: >=4.8.4 <6.1.0`. Tailwind v4 removed
`tailwind.config.js` in favour of CSS-first configuration.

## Options considered

1. **TypeScript 7.0 and accept unsupported-peer warnings.** Fastest type checking, but lint rules
   that need type information may break in subtle ways.
2. **TypeScript 7.0 without type-aware linting.** Loses valuable rules (`no-floating-promises`,
   `no-unsafe-*`).
3. **TypeScript 6.0.3**, the newest version typescript-eslint supports; keep `strictTypeChecked`.

## Decision

- Option 3: TypeScript 6.0.3. Revisit when typescript-eslint supports 7.x (Dependabot will propose it).
- Tailwind v4 via `@tailwindcss/vite`. Raw tokens live in `src/styles/tokens.css` as CSS variables.
  `globals.css` maps them with `@theme inline`, which replaces the brief's "Tailwind config".
- ESLint 10 flat config with `typescript-eslint` `strictTypeChecked`, react-hooks and react-refresh,
  plus project rules: `no-explicit-any` is an error, and `dangerouslySetInnerHTML` is banned
  (Section 5.3).
- Strict compiler flags beyond `strict`: `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`.
- Exact versions in `package.json` (`npm install -E`) and a committed lockfile.

## Consequences

- **Pros:** type-aware linting catches real bugs (unhandled promises, unsafe `any` flows); no peer
  conflicts; tokens drive both light and dark themes with zero JS.
- **Cons:** type checking is slower than with TS 7; `exactOptionalPropertyTypes` makes some library
  typings stricter to work with.

## Simple explanation

We pick the newest tool versions that work well together today, rather than the newest of each on
its own. Colours and spacing are defined once, as CSS variables, and both themes read from them.
