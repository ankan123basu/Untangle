# ADR-024: How 3D and motion are built, and the budget they must respect

- **Status:** Accepted
- **Date:** 2026-11-18
- **Phase:** 9

## Context

The brief asks for an immersive feel (§12.3): a 3D login hero, a living background, route and
shared-element transitions, KPI tilt, a resolve micro-interaction, and suggests React Bits
(Aurora, ClickSpark) and Motion `layoutId`. It also sets a hard budget: three.js lazy-loaded,
Lighthouse ≥ 90 for performance and accessibility on the dashboard, reduced motion respected, a
WebGL fallback. Agents use the tool all day, so nothing may slow the work screens.

## Options considered

1. **Use the suggested libraries as-is.** Aurora (WebGL shader background) and ClickSpark from
   React Bits; Motion for route transitions, `layoutId` and tilt; drei helpers for the 3D scene.
2. **Pick per effect, cheapest tool that does the job.** CSS where CSS is enough, platform APIs
   where they exist, libraries only inside lazy chunks.

## Decision

Option 2:

| Effect | Built with | Why not the suggestion |
|---|---|---|
| Login hero (knot → checkmark, glass ticket cards, parallax) | R3F + three.js, own chunk; no drei | drei's helpers weren't needed and add weight |
| App background | CSS: three blurred colour fields, transform-only animation, paused when the tab is hidden | Aurora runs a WebGL shader on every screen all day; vendored copy had 42 type errors |
| Route transition | CSS `animate-rise` keyed by path | Motion in the shell put ~46 kB gz on every page's critical path |
| Ticket number list → detail | React 19 `<ViewTransition name>` (React Router wraps navigations in transitions) | Motion `layoutId` can't span lazily loaded routes cleanly; the router's `viewTransition` prop is deprecated in favour of this |
| KPI tilt | pointer events + CSS transform | no library needed |
| Status stepper | Motion (spring), only in the ticket detail chunk | the one place a spring adds clarity |
| Resolve micro-interaction | small Web Animations API spark burst | ClickSpark had 29 type errors and binds to every click |

Fallbacks: no WebGL, low-power (< 4 cores or Save-Data), narrow screens or reduced motion → the
static hero. Reduced motion also stops the background, route, list and stepper animations and
the view-transition morph.

Budget rules (verified with `make lighthouse`): heavy widgets below the fold wait until the key
numbers have arrived (`WhenVisible` with `ready` + `delay`); frames render immediately with
placeholder numbers so nothing shifts.

## Consequences

- **Pros:** dashboard 100/100/100 on desktop and 91/100/100 on simulated mobile; main bundle
  86 kB gz; three.js (243 kB gz) only on the desktop login page; every effect degrades to nothing
  rather than to something broken.
- **Cons:** fewer React Bits components than the brief listed (CountUp, SpotlightCard, ShinyText
  are used); the chart appears ~1 s after the numbers; view transitions are Chromium/Safari only
  (others navigate instantly, which is the right fallback).

## Simple explanation

The fancy parts are there, but each one uses the lightest tool that works, and nothing heavy
loads before the numbers an agent needs. If a device can't or shouldn't animate, it simply doesn't.
