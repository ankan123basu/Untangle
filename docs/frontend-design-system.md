# Frontend design system (loud neo-brutalism)

> Tokens and primitives: Phase 7. Motion, 3D and the app background: Phase 9. Frosted glass and
> colour themes: [ADR-025](decisions/ADR-025-frosted-glass-colour-themes-and-ambient-3d.md).
> Nebula glass: ADR-026. **Current look: loud neo-brutalism with twelve palettes and toon 3D,
> [ADR-027](decisions/ADR-027-loud-neo-brutalism-and-toon-3d.md)** (supersedes 025/026 where they differ).

## Simple explanation

Untangle is loud neo-brutalism:
- thick black outlines and hard offset shadows that grow on hover and slam flat on click
- flat "sticker" colours on cards, over a dotted engineering-paper background
- page titles marked with a highlighter stroke

The Appearance menu (palette icon) lets each person pick light, dark or system mode and one of
twelve pop colours: Banana, Bubblegum, Mint, Sky, Lavender, Tangerine, Lime, Coral, Teal, Cobalt,
Grape or Cherry. In dark mode the shadows turn into the pop colour.

3D is drawn the same way (flat cartoon colours, ink outlines, solid shadows), only in its own
boxes, and always about the product:
- On the login, a support desk: a ticket gets stamped RESOLVED, with an SLA stopwatch,
  escalation levels and the HMAC padlock around it.
- On the dashboard, the unresolved queue as stacks of tickets per priority.

Everything works with the keyboard and with "reduce motion" switched on.

## Technical details

### Where things live

| Path | Contents |
|---|---|
| `src/styles/tokens.css` | every design value as a CSS variable (`--ut-*`), light + dark |
| `src/styles/globals.css` | Tailwind v4 import, `@theme inline` mapping, base styles, `.glass-panel`, `.solid-panel` (gradient edge), `.app-backdrop`, `.login-stage`, `.btn-glow`, `.nav-active`, `.text-gradient`, `.text-aurora` |
| `src/components/ui/` | first-party primitives (below) |
| `src/components/reactbits/` | vendored React Bits (ADR-023) |
| `src/components/layout/` | app shell, command palette, offline banner, splash |
| `src/components/brand/` | logo |

Components never use raw hex values; they use Tailwind utilities generated from the tokens
(`bg-paper`, `text-ink`, `border-line`, `shadow-soft`, `bg-brand-gradient`, `bg-prio-critical-bg`,
`ease-smooth`, ...).

### Tokens

| Group | Tokens | Notes |
|---|---|---|
| Colour theme | `--ut-pop`, `--ut-on-pop`, `--ut-wash` (page tint), `--ut-pop-ink` (links/focus) | twelve themes on `<html data-accent>`; on-pop ≥ 4.97:1, pop-ink ≥ 4.73:1 on its wash |
| Stickers | `--ut-stk-{pink,mint,sky,lilac,peach,lemon}` (`bg-stk-*`) | always with `#111` text (`.sticker`) |
| Brand | `brand`, `brand-2`, `glow`, `on-brand` (white), `accent`, `on-accent`; utility `bg-brand-gradient` | gradient = primary action/selection; accent = links and focus |
| Surfaces | `paper`, `bg-base` (backdrop), `paper-2`, `surface` (86 %), `glass` (70 % / 62 % dark), `glass-strong` (86 % / 80 %), `glass-edge`, `edge` (gradient hairline), `star-opacity` (dark only) | long text sits on `solid-panel` (`glass-strong`) |
| Ink | `ink`, `muted`, `line`, `line-soft`, `field-line` | `field-line` gives form controls ≥ 3:1 (WCAG 1.4.11) |
| Feedback | `danger`, `success`, `warning` (+ `on-*`) | |
| Priority / status / SLA | `prio-{low,medium,high,critical}-{fg,bg}`, `status-*-{fg,bg}`, `sla-{ok,risk,breached}` | fg/bg pairs ≥ 4.5:1 in both themes |
| Shape | 1 px edges, radius 8/14/22 px, `shadow-soft-sm/soft/soft-lg` (blurred, accent-tinted) | pills for buttons, nav and chips |
| Motion | 120/200/360 ms, `ease-smooth` cubic-bezier(0.2, 0.8, 0.2, 1) | all disabled under reduced motion |

Appearance (`AppearanceMenu`, in the app header and on the login page): mode light / dark /
system, twelve colour themes, and a "3D scenes" switch (login desk + dashboard ticket stacks). Dark applies when the OS prefers it, unless
the user forced light (`data-theme="light"`), or when forced (`data-theme="dark"`).
`public/theme-init.js` sets `data-theme` and `data-accent` from `localStorage` before first paint
(a file, not inline, because the CSP forbids inline scripts). Hooks: `useTheme`, `useAccent`,
`useScene3d` in `hooks/use-theme.ts`.

### Primitives (`components/ui`)

| Component | Notes |
|---|---|
| `Button` | pill; variants primary (gradient)/secondary (frosted)/ghost/danger, sizes; `loading` → disabled + `aria-busy`; defaults to `type="button"`; lifts on hover, sinks on press |
| `Card` (+ Header/Title/Body) | `tone="solid"` or `"glass"` |
| `Field`, `Input`, `Textarea`, `Select` | `Field` is a render prop that wires `id`, `aria-invalid`, `aria-describedby` (hint + error) |
| `Badge` | priority/status/SLA tones |
| `Dialog` | native `<dialog>` (focus trap, Esc, top layer for free) |
| `Tooltip` | CSS hover/focus tooltip, also used to explain *why* an action is disabled |
| `Skeleton`, `EmptyState`, `ErrorState`, `Kbd`, `Spinner` | `ErrorState` shows the server `traceId` for support |

### React Bits in use

CountUp (dashboard numbers). ShinyText, SpotlightCard and TiltCard were removed (ADR-026/027). Each local
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

### Screens (Phase 8)

| Route | Screen | Notes |
|---|---|---|
| `/` | Dashboard | CountUp unresolved total + priority bar; SpotlightCard KPIs linking to filtered lists; trend chart (lazy chunk, with a table alternative); workload for leads/admins; the shared ticket table |
| `/tickets` | Ticket list | every filter in the URL; quick views; debounced search; sort; page size; skeleton / empty / error states |
| `/tickets/:id` | Ticket detail | header + live SLA countdown; status stepper; action bar from `allowedActions` (denied → disabled + reason tooltip); timeline; composer (Ctrl+Enter); details + assignee picker with workload; AI panel; edit, action and conflict dialogs; closed banner |
| `/tickets/new` | Create | RHF + Zod mirroring the backend; AI triage chips; assignee picker (leads) or "Assign to me" (agents); server field errors inline |
| `/board` | Kanban (bonus) | drag-and-drop or "Move to…" select; each move checks the server's `allowedActions` first |
| `/admin/agents` | Agents | add, change role, deactivate with bulk-reassign flow |
| `/admin/partners` | Partners | add, rotate (secret shown once, copy button), enable/disable, link to logs |
| `/admin/webhooks` | Webhook logs | filters (partner, outcome, dates) in the URL; linked tickets |

Below `lg` (1024 px) the sidebar becomes a drawer (menu button, Esc or a tap outside closes it).
A thin brand-coloured bar shows while a lazy route chunk loads.

### Motion and 3D (Phase 9, [ADR-024](decisions/ADR-024-motion-and-3d-budget.md))

| Where | What | Reduced motion / fallback |
|---|---|---|
| Login (≥ 1024 px) | `desk-scene.tsx` (ADR-027), toon + ink outlines + hard shadows: a paper ticket slides in, the rubber stamp slams RESOLVED (speed lines, tiny shake), it flies out and the next number comes in; SLA stopwatch ticks; escalation chevrons light L1 → L3; HMAC padlock clicks shut; cursor tilt | static HTML/CSS desk (reduced motion, no WebGL, < 4 cores, Save-Data, or "3D scenes" off) |
| Dashboard hero (≥ 1024 px) | `ticket-stacks-scene.tsx`: one stack per priority, one slab per unresolved ticket (max 7), dropping in with a bounce; the critical stack's top wobbles. **Interactive:** hover lifts a stack and labels it ("High · 1 open"), click opens that priority's unresolved tickets, drag turns the set (springs back; a drag never counts as a click) | flat CSS stacks with the same hover and click |
| Every page | `.app-backdrop`: the theme wash with a dot grid (static CSS) | n/a |
| Login | `.marquee` ticker band | frozen |
| Route change | page fades and rises 6 px (`animate-rise`) | none |
| Ticket list → detail | the ticket number morphs into the header (`<ViewTransition name="ticket-…">`) | instant |
| Lists (tickets, timeline) | staggered rise, first 10 items only (`stagger()`) | none |
| KPI cards, priority chips, demo buttons | `.press`: lift + bigger shadow on hover, slam flat on press | instant |
| Status stepper | spring on the progress line and active step | instant |
| Resolve | spark burst from the centre | none |

Motion timing tokens: 120 / 200 / 360 ms with `--ut-ease`. Durations stay short so the UI never
waits on an animation.

### Performance rules

- Every route is its own chunk; three.js, Recharts, cmdk (palette) and Motion are never in the
  main bundle.
- No WebGL behind content (ADR-026). The dashboard orbit mounts via `WhenVisible` after the
  numbers; an earlier global 3D layer mounted during start-up dropped desktop Lighthouse to 69
  (TBT 940 ms), which is why every scene waits for the first paint.
- Dashboard frames render at once with placeholder numbers (no layout shift); the chart and the
  queue load after the key numbers, when visible.
- `make lighthouse` measures the signed-in dashboard (mobile + desktop).

### Gotcha: unknown Tailwind classes are silently ignored

Lighthouse caught critical badges rendering black-on-red: components used `text-prio-critical-fg`
but the theme only defined `--color-prio-critical`. The theme now defines the `-fg` names too.
When adding colour utilities, check the class exists in `@theme`.
