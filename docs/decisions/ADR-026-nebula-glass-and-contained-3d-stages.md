# ADR-026: Nebula glass, sixteen colour themes and contained 3D stages

- **Status:** Accepted (amends ADR-025: the ambient 3D layer behind signed-in pages is removed)
- **Date:** 2026-11-18
- **Phase:** post-12 (second design pass requested by the owner)

## Simple explanation

After the first frosted-glass pass, the owner had two complaints. The 3D shapes floating behind
the app pages made the app look messy, because they peeked out from behind cards and tables. And the
login page was still too plain for a first impression. They asked for an "out of the world"
premium look, more colours to choose from, 3D that is about the product itself, and nothing
overlapping.

So the look became **nebula glass**:

- Frosted panels have a thin gradient edge and a top highlight.
- Behind them is a slowly moving aurora: pearl white by day, deep space with stars by night.
- There are sixteen colour themes. Each one is a four-colour aurora.

3D now lives only in its **own boxes**:

- **On the login page**, four glowing threads, one per priority, start tangled. They are pulled
  straight into a frosted ticket card, the ticket flips to RESOLVED, and the story loops with the
  next ticket number. The scene has a column to itself. The headline sits below it and the form
  card sits to its right, so nothing overlaps.
- **On the dashboard**, a "live queue" orbit sits inside the unresolved-tickets card. Each real
  unresolved ticket is a small glowing card on its priority's ring, and critical tickets orbit
  closest and fastest.

## Technical details

### Options considered

| Option | Pros | Cons |
|---|---|---|
| Keep the ambient 3D layer, tone it down | already built | still competes with content; GPU blur over a moving canvas on every page |
| **3D only in dedicated stages (chosen)** | 3D tells the product's story; content is never covered; work pages cost no GPU time | needs a stage in the layout |
| No 3D at all | cheapest | fails the brief ("3D immersive experience") |

### Decision

1. **Tokens.**
   - Every theme gains `--ut-a4`, a second aurora hue.
   - New tokens `--ut-edge` (gradient hairline), `--ut-bg-base` and `--ut-star-opacity`.
   - Dark mode is a deeper space colour; glass is 62% / 80% opaque.
   - Sixteen themes: violet (default), aurora, nebula, indigo, ocean, arctic, teal, emerald,
     jungle, amber, sunset, lava, rose, sakura, fuchsia, graphite.
   - White text measures ≥ 5.0:1 on both gradient stops of every theme (WCAG relative-luminance
     check, run when the themes were chosen).
2. **Glass.**
   - `.glass-panel` / `.solid-panel` draw the edge as a masked `::before`, so it follows the
     radius.
   - Primary buttons and the active nav pill glow in the theme colour (`.btn-glow`, `.nav-active`).
     Primary buttons also get a light sweep on hover.
3. **Backdrop.** `.app-backdrop` uses CSS only:
   - five drifting fields in a1–a4
   - a starfield (`::before`, visible only in dark mode)
   - grain (`::after`)
4. **Login** (`login-hero.tsx`, `untangle-scene.tsx`).
   - The page is always deep space (`.login-stage`) with white text; the form card follows the mode.
   - From `lg`, two grid columns: the hero column has its own rows for brand, the 3D stage
     (`flex-1`), the headline and four fact tiles.
   - Scene contents:
     - Threads: instanced beads, 4 × 230 core plus 4 × 230 additive glow.
     - Ticket card: a frosted `MeshPhysicalMaterial` with a canvas-texture face, a check badge
       and a pulse ring.
     - Depth: stars and outline "ghost" tickets.
     - Cursor parallax.
   - The same story is drawn in SVG as the fallback.
5. **Dashboard** (`queue-orbit.tsx`, `queue-orbit-scene.tsx`).
   - The orbit is inside the hero card, `lg` and up.
   - Counts come from `unresolvedByPriority`, capped at 14 per ring; the exact numbers are beside it.
   - It mounts via `WhenVisible`, after the numbers.
   - The fallback is a static CSS orbit.
6. **Gates (unchanged from ADR-024).**
   - `canRunScene3d`: WebGL, ≥ 4 cores, no Save-Data, minimum width, no reduced motion.
   - The Appearance "3D scenes" switch, which now controls these two stages.
   - Rendering pauses while the tab is hidden.
   - three.js stays in lazy chunks.
7. **Removed.**
   - `AmbientLayer` / `ambient-scene.tsx`
   - the old `hero-scene.tsx`
   - the ShinyText tagline: it couldn't read CSS-variable colours and rendered near-black.

### Consequences

- Work screens (lists, board, ticket detail) carry no WebGL at all, only CSS.
- Login and dashboard each load one small R3F chunk on capable desktops.
- Two stages to maintain instead of one global layer; each has a static fallback and a test (the
  login one in `auth.test.tsx`).
