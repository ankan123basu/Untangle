/**
 * The page wash behind every signed-in screen (ADR-027): the theme's tint with a dot grid, like
 * engineering paper. Pure CSS (globals.css `.app-backdrop`), static, no WebGL, nothing to pause.
 */
export function AppBackdrop() {
  return <div aria-hidden="true" className="app-backdrop" />;
}
