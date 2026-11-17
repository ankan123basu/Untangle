# Frontend design system (Glass-Brutalism)

> Tokens and primitives: Phase 7. Motion, 3D and the app background: Phase 9 (sections marked).

## Simple explanation

Untangle looks like paper and ink: warm off-white background, thick black outlines, hard offset
shadows (no blur) and one bold "untangled" yellow. Floating layers (header, dialogs, palette) are
frosted glass so content stays visible behind them. Everything works in light and dark mode, with
the keyboard, and with "reduce motion" switched on.

## Technical details

### Where things live

| Path | Contents |
|---|---|
| `src/styles/tokens.css` | every design value as a CSS variable (`--ut-*`), light + dark |
| `src/styles/globals.css` | Tailwind v4 import, `@theme inline` mapping, base styles, `.glass-panel`, `.solid-panel` |
| `src/components/ui/` | first-party primitives (below) |
| `src/components/reactbits/` | vendored React Bits (ADR-023) |
| `src/components/layout/` | app shell, command palette, offline banner, splash |
| `src/components/brand/` | logo |

Components never use raw hex values; they use Tailwind utilities generated from the tokens
(`bg-paper`, `text-ink`, `border-line`, `shadow-hard`, `bg-prio-critical-bg`, `ease-brutal`, ...).

### Tokens

| Group | Tokens | Notes |
|---|---|---|
| Brand | `brand` #ffd23f, `on-brand`, `accent` #3442c4, `on-accent` | yellow = primary action/selection; blue = links and focus |
| Surfaces | `paper`, `paper-2`, `surface`, `glass` (80%), `glass-strong` (92%) | text only on glass ≥ 78% opaque |
| Ink | `ink`, `muted`, `line`, `line-soft` | `line` is the brutalist outline colour |
| Feedback | `danger`, `success`, `warning` (+ `on-*`) | |
| Priority / status / SLA | `prio-{low,medium,high,critical}-{fg,bg}`, `status-*-{fg,bg}`, `sla-{ok,risk,breached}` | fg/bg pairs ≥ 4.5:1 in both themes |
| Shape | borders 2/3 px, radius 8/10/12 px, `shadow-hard-sm/hard/hard-lg` (2/4/6 px offsets) | |
| Motion | 120/200/360 ms, `ease-brutal` cubic-bezier(0.2, 0.8, 0.2, 1) | all disabled under reduced motion |

Theme: dark applies when the OS prefers it, unless the user forced light (`data-theme="light"`), or
when forced (`data-theme="dark"`). `public/theme-init.js` sets the attribute from `localStorage`
before first paint (a file, not inline, because the CSP forbids inline scripts). `useTheme` cycles
system → light → dark.

### Primitives (`components/ui`)

| Component | Notes |
|---|---|
| `Button` | variants primary/secondary/ghost/danger, sizes; `loading` → disabled + `aria-busy`; defaults to `type="button"`; press = shadow collapse |
| `Card` (+ Header/Title/Body) | `tone="solid"` or `"glass"` |
| `Field`, `Input`, `Textarea`, `Select` | `Field` is a render prop that wires `id`, `aria-invalid`, `aria-describedby` (hint + error) |
| `Badge` | priority/status/SLA tones |
| `Dialog` | native `<dialog>` (focus trap, Esc, top layer for free) |
| `Tooltip` | CSS hover/focus tooltip, also used to explain *why* an action is disabled |
| `Skeleton`, `EmptyState`, `ErrorState`, `Kbd`, `Spinner` | `ErrorState` shows the server `traceId` for support |

### React Bits in use

CountUp (dashboard numbers), SpotlightCard (KPI cards), ShinyText (login tagline). Each local
change is marked `// untangle:`. Aurora and ClickSpark: decided in Phase 9.

### Accessibility rules

- Visible focus ring everywhere (`:focus-visible`, 3 px accent outline).
- Skip link to `<main>`; landmarks: `nav`, `header`, `main`.
- Colour is never the only signal: priority/status badges always include text.
- `prefers-reduced-motion`: transitions and animations are cut to ~0.
- Forms: errors announced via `aria-describedby`; server errors in `role="alert"`.

### Fonts

Space Grotesk (display), Inter (body), JetBrains Mono (ticket ids, code), self-hosted with
`@fontsource-variable`. Vite is told never to inline fonts as `data:` URIs because the CSP allows
only `font-src 'self'` (found during the Phase 7 browser check).

### Motion and 3D *(Phase 9)*

_Filled in Phase 9._
