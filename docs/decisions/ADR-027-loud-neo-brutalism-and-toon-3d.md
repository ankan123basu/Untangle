# ADR-027: Loud neo-brutalism, twelve palettes and toon 3D about the product

- **Status:** Accepted (supersedes the look of ADR-025 and ADR-026; ADR-024's budget still applies)
- **Date:** 2026-11-18
- **Phase:** post-12 (third design pass, requested by the owner)

## Simple explanation

The owner rejected the frosted-glass redesign. It lost the neo-brutalist identity Untangle
started with, and its 3D shapes (glowing threads, an orbiting sphere) had nothing to do with
support tickets. They asked for the neo-brutalist UI back, made far bolder, with 3D models that
are clearly about the product.

So Untangle is neo-brutalist again, louder:

- thick black outlines, hard offset shadows that grow when you hover and slam flat when you click
- sticker-coloured cards (pink, peach, sky, mint)
- a dotted "engineering paper" background
- page titles marked with a highlighter stroke
- a scrolling ticker band on the login

There are twelve colour themes, each a loud "pop" colour: Banana (default), Bubblegum, Mint, Sky,
Lavender, Tangerine, Lime, Coral, Teal, Cobalt, Grape, Cherry. In dark mode the shadows turn into
the pop colour.

The 3D is drawn the same way: flat cartoon colours, ink outlines, solid black shadows. Every object
is a piece of Untangle:

- **On the login, a support desk.**
  - A paper ticket (`UNT-000123`) slides in, a rubber stamp slams **RESOLVED** onto it, and the
    next ticket number comes in.
  - An SLA stopwatch ticks.
  - Escalation chevrons light up L1 → L2 → L3.
  - An **HMAC** padlock clicks shut as each partner ticket arrives.
- **On the dashboard, ticket stacks.** The real unresolved queue is shown as physical stacks of
  tickets, one per priority, one slab per ticket. The slabs drop in with a bounce, and the top of
  the critical stack wobbles while it is not empty.

Both scenes sit in their own boxes; nothing overlaps text or controls.

## Technical details

### Options considered

| Option | Pros | Cons |
|---|---|---|
| Keep the glass look, change only the 3D | least work | the owner rejected the look itself |
| Restore the Phase 7 neo-brutalism as it was | proven, fast | the owner called the original "basic" too |
| **Restore it and push it further; toon 3D in the same language (chosen)** | one coherent visual language from CSS to WebGL; 3D explains the product | two scenes to maintain |

### Decision

1. **Base.** The frontend files from before ADR-025 were restored from git history (commit
   `7ccbea6`).
2. **Tokens** (`tokens.css`).
   - Per theme: `--ut-pop`, `--ut-on-pop`, `--ut-wash` and `--ut-pop-ink` on
     `<html data-accent>`.
   - Fixed sticker fills `--ut-stk-*`, which always carry `#111` text.
   - Contrast: on-pop ≥ 4.97:1, pop-ink ≥ 4.73:1 on its wash.
   - Panels: 3 px outline and a 5 px hard shadow; dark mode turns the shadow into the pop colour.
3. **Components** (`globals.css`).
   - `.press`: lifts and the shadow grows on hover; pressing translates it flat.
   - `.sticker`, `.highlight` (marker stroke) and `.marquee`.
   - `.app-backdrop` is a static dot grid. CSS only; there is nothing to animate or pause.
4. **Appearance menu.** Brutalist swatches, light/dark/system, and the "3D scenes" switch. The
   old `ThemeToggle` is gone.
5. **Shared toon helpers** (`components/three/toon.ts`, `inked.tsx`).
   - A 3-step `gradientMap` for cel shading.
   - Ink outlines: back faces pushed out along their normals in a small `ShaderMaterial`.
   - Hard shadows from `shadows="basic"` on a `ShadowMaterial` plane, in `useShadowColor()`
     (ink by day, the pop colour at night).
6. **Login** (`desk-scene.tsx`).
   - An 8 s loop driven by one `storyAt(t)`.
   - The ticket face is a canvas texture; it is redrawn only when the ticket number or the
     stamped state changes.
   - The fallback is a static HTML/CSS desk. It has a test in `auth.test.tsx`.
7. **Dashboard** (`ticket-stacks.tsx`, `ticket-stacks-scene.tsx`).
   - Counts come from `unresolvedByPriority`, capped at 7 slabs; the exact numbers are beside it.
   - It mounts via `WhenVisible`. The fallback is flat CSS stacks.
   - Interactive, added at the owner's request:
     - **One slab per real ticket.** The stage fetches the 10 most urgent unresolved tickets per
       priority (`/tickets?priority=X&status=OPEN&status=IN_PROGRESS&sort=slaDueAt,asc&size=10`,
       live every 20 s, the list's query keys). Anything beyond that is a dark "+N more" cap.
     - Slabs get thinner as a stack grows (`MAX_HEIGHT / count`), so even a full stack fits the
       stage and every ticket stays big enough to hover.
     - Hover a slab: it slides out toward you, and the read-out shows number, subject, assignee
       and SLA state. The subject shortens first; the who and when always stay visible.
     - Click a slab: opens that ticket. Click a base or the cap: opens the priority's list.
     - Drag turns the set (±0.75 rad) and it springs back; a drag never counts as a click.
     - **No wait.** The scene chunk starts downloading when the dashboard mounts; it used to wait
       for `WhenVisible` plus an idle delay, which the owner saw as the stage "coming late".
       Trade-off: three.js now loads in parallel with the dashboard's data on desktop. Re-check
       with `make lighthouse`.
     - Pointer-only and `aria-hidden`; the priority chips and the queue table are the keyboard
       and screen-reader path to the same tickets.
     - The flat fallback has the same per-ticket behaviour, unit-tested in
       `ticket-stacks.test.tsx` with 25 low tickets → 10 slabs + "+15".
8. **Fix.** The header is `relative z-30`, so the Appearance and notification menus open above
   page content. Before, they opened underneath it.
9. **Removed.**
   - `untangle-scene`, `queue-orbit*` and `theme-colors`
   - `ShinyText`, which rendered near-black with CSS-variable colours
   - `SpotlightCard` and `TiltCard`, replaced by sticker cards

### Consequences

- One visual language everywhere, and the 3D now explains the product: tickets, SLA, escalation,
  signed webhooks.
- The same gates as before:
  - three.js is in lazy chunks only, never on phones, never with reduced motion
  - it can be switched off
  - rendering pauses while the tab is hidden
