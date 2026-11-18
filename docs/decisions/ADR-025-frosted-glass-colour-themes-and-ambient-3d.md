# ADR-025: Frosted glass, ten colour themes and an ambient 3D layer

- **Status:** Superseded by [ADR-027](ADR-027-loud-neo-brutalism-and-toon-3d.md); earlier amended by [ADR-026](ADR-026-nebula-glass-and-contained-3d-stages.md) (the ambient 3D layer was removed; themes grew to sixteen). Supersedes the "Glass-Brutalism" look of MASTER_PROMPT §12.2; extends ADR-024
- **Date:** 2026-11-18
- **Phase:** post-12 (design refresh requested by the owner)

## Context

The owner found the Glass-Brutalism UI (paper and ink, 2 px black outlines, hard offset shadows,
one yellow) too basic. They asked for a premium, frosted-glass look with lots of colours to choose
from and immersive 3D, and pointed to a modern landing page for the feel: a vivid gradient
background, translucent pill navigation, large rounded glass cards, a saturated accent and a 3D
hero object.

ADR-024's budget still applies: Lighthouse ≥ 90 on the dashboard, three.js out of the main
bundle, reduced motion respected, a fallback without WebGL. ADR-024 had kept R3F to the login page
(R11); this ADR adds a 3D layer to the signed-in screens, so it has to earn its place within
that budget.

## Options considered

1. **Restyle every screen by hand.** About 90 class lists across 30 files; easy to miss one and
   hard to keep consistent.
2. **Change the design system, not the screens.** Rewrite the tokens and the shared primitives
   (`.glass-panel`, `Button`, `Field`, `Badge`, `Card`), then sweep the few brutalist utilities
   (`border-2`, `shadow-hard*`, `ease-brutal`, solid `bg-brand`) mechanically.
3. **For 3D: a WebGL shader background (Aurora-style) on every screen.** Runs a full-screen shader
   at 60 fps all day.
4. **For 3D: a small scene of real objects behind the glass, rendered on demand at 30 fps, mounted
   only after the page is idle.**

## Decision

Options 2 and 4.

**Look.** Panels are frosted glass: 72 % (light) / 68 % (dark) translucent fill,
`backdrop-filter: blur(22px) saturate(1.7)`, a bright 1 px edge, an inner top highlight and a
soft shadow tinted with the accent. Data-heavy panels and dialogs use denser glass (86 % / 84 %).
Buttons, nav items and chips are pills; primary actions use the accent gradient. Radii grow to
8 / 14 / 22 px. Browsers without `backdrop-filter` get an opaque surface.

**Colour themes.** Ten (`violet` default, `indigo`, `ocean`, `teal`, `emerald`, `amber`,
`sunset`, `rose`, `fuchsia`, `graphite`), each five tokens: `a1`/`a2` (the gradient; white text
is ≥ 4.5:1 on both stops), `a3` (a light decorative glow) and accent text for light and dark
backgrounds. Everything else (paper tint, mesh, shadows, logo, charts, 3D materials) derives
from them, so a theme is about ten lines of CSS. The choice lives on `<html data-accent>` and in
`localStorage`, applied before first paint by `theme-init.js`, next to light/dark.

**3D.** `AmbientLayer` mounts a lazy R3F scene between the CSS gradient mesh and the glass:
an iridescent torus knot (the tangle), a glowing checkmark (the tangle, solved), frosted ticket
cards, orbs and a ring. It uses the theme colours, follows the cursor and page scroll, and
reflects a studio environment generated on the GPU (no HDR download). The login hero keeps its
knot-to-checkmark story, recoloured with the theme on a deep gradient panel.

Cost controls: on-demand rendering at 30 fps; paused while the tab is hidden; DPR ≤ 1.5; mounted
only after `load` + `requestIdleCallback` + 1.2 s, then faded in; skipped below 768 px, on fewer
than 4 cores, with Save-Data, without WebGL, with reduced motion, or when the person turns
"3D scenes" off in the new Appearance menu (which replaces the light/dark toggle).

## Consequences

- **Pros:** the premium, colourful look the owner asked for, with a choice of ten themes and light
  or dark mode; real depth behind the glass. Lighthouse on the dashboard after the change is
  desktop 99/100/100 and mobile 100/100/100 (perf/a11y/best practices). Contrast rules are
  unchanged: priority, status and SLA pairs are untouched, and text panels stay ≥ 72 % opaque.
- **Cons:** `backdrop-filter` over a moving canvas costs GPU time on large screens (hence 30 fps
  and the off switch). The first desktop measurement, with the scene mounting during start-up,
  was 69 (TBT 940 ms); deferring it to idle fixed that, and the deferral has to stay. The 3D
  layer is decorative, so it isn't covered by unit tests; its gates (capability, user switch)
  are. Colour themes are per browser, not per account.

## Simple explanation

The app now looks like frosted glass floating over a colourful background, with glossy 3D shapes
drifting behind it. You can pick one of ten colour themes, light or dark mode, and turn the 3D
off. The 3D only starts after the page has finished loading, never runs on phones or when
"reduce motion" is on, and the app is just as fast as before.
