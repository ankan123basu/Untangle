# How it works — Phase 9: 3D, motion and polish

## Simple explanation

Untangle now feels alive without getting in the way. The login page shows the logo's idea in 3D:
a tangled line pulls straight into a checkmark while little ticket cards float around it. Inside
the app a soft colour wash drifts behind frosted panels, pages glide in, a ticket's number flows
into the ticket page when you open it, and resolving a ticket gives a small burst of sparks.
Anyone who turns on "reduce motion" gets a calm, still interface, and older devices get a static
picture instead of 3D. Every screen was checked by automated accessibility tools, and the
dashboard scores 91–100 on Google Lighthouse.

## Technical details

### Pieces

| File | Role |
|---|---|
| `features/auth/hero-scene.tsx` | R3F scene (own chunk): `TubeGeometry` rebuilt per frame while the knot→check morph runs (~2 s), then static; cards float; group follows the pointer |
| `features/auth/hero-support.ts` | `canRunHeroScene()`: reduced motion, Save-Data, < 4 cores, < 1024 px or no WebGL → static hero |
| `features/auth/login-hero.tsx` | lazy-loads the scene inside an error boundary (a WebGL failure keeps the static art) |
| `components/layout/app-backdrop.tsx` + `.app-backdrop` CSS | the drifting colour fields, paused on `visibilitychange` |
| `components/ui/tilt-card.tsx` | pointer tilt for KPI cards (no library) |
| `components/ui/when-visible.tsx` | defers heavy widgets until visible, ready and after a short delay |
| `lib/motion.ts` | `stagger()` delays and the shared view-transition name |
| `hooks/use-global-shortcuts.ts` | keyboard shortcuts; the palette UI itself is now a lazy chunk |

Design choices and the budget are in [ADR-024](../decisions/ADR-024-motion-and-3d-budget.md).

### Accessibility pass

- `src/test/a11y.test.tsx` runs **axe-core** on the app shell and all nine screens with realistic
  data (10 tests). Colour contrast is left to Lighthouse (jsdom can't compute colours).
- Fixed: loading skeletons used `aria-label` on a plain `div` (now `role="status"`); the chart
  was `aria-hidden` but focusable (Recharts' keyboard layer, now off: the data table is the
  accessible version); critical/status badges had the wrong text colour (see below).

### Lighthouse (signed-in dashboard, Docker production build, `make lighthouse`)

| Run | Performance | Accessibility | Best practices | LCP | CLS | TBT |
|---|---|---|---|---|---|---|
| Phase 8 baseline, mobile | 73 | 93 | 100 | 5.1 s | 0 | 80 ms |
| Phase 8 baseline, desktop | 98 | 93 | 100 | 1.0 s | 0 | 0 ms |
| **Phase 9, mobile** (simulated slow 4G, 4× CPU) | **91** | **100** | **100** | 3.5 s | 0 | 40–50 ms |
| **Phase 9, desktop** | **100** | **100** | **100** | 0.8 s | 0–0.013 | 0 ms |

What moved the numbers: Motion and cmdk out of the main bundle (main 104 → 86 kB gz), the
dashboard renders its frames immediately with placeholder numbers (CLS 0.058 → 0), and the chart
(Recharts, 116 kB gz) and the queue load after the key numbers. The simulated mobile LCP (3.5 s) is
still above the 2.5 s goal of §12.3 because the dashboard needs a token refresh and its data before
it can paint; the observed LCP on the local stack is about 0.2 s.

### Bugs found

| Found by | Problem | Fix |
|---|---|---|
| Lighthouse | `text-prio-*-fg` / `text-status-*-fg` classes didn't exist in the theme, so badge text fell back to black (3.3:1 on red) | added `-fg` colour names to `@theme` |
| Lighthouse | chart hidden from screen readers but keyboard-focusable | `accessibilityLayer={false}` + table alternative |
| axe | `aria-label` on plain loading `div`s | `role="status"` |
| code review | palette links used `?view=mine` / `?view=breached`, which the filters ignore | link to the real quick-view queries |
| browser | hero checkmark crossed the headline | scene confined to the upper band; headline anchored at the bottom |

### Not verified

Real low-end phones (only Lighthouse simulation); Firefox, which has no View Transitions and so
navigates instantly by design.
