import { useEffect, useState } from 'react';

const FIELDS = [
  { color: 'var(--ut-a3)', size: '52vmax', top: '-22vmax', left: '-14vmax', dx: '9vw', dy: '7vh' },
  { color: 'var(--ut-a1)', size: '42vmax', top: '40vh', left: '62vw', dx: '-8vw', dy: '-9vh' },
  { color: 'var(--ut-a4)', size: '34vmax', top: '70vh', left: '2vw', dx: '7vw', dy: '-6vh' },
  { color: 'var(--ut-a2)', size: '28vmax', top: '-8vh', left: '68vw', dx: '-6vw', dy: '8vh' },
  { color: 'var(--ut-a4)', size: '20vmax', top: '28vh', left: '26vw', dx: '5vw', dy: '6vh' },
] as const;

/**
 * The aurora behind the glass panels (§12.3, ADR-026) in the current colour theme's four hues,
 * with a starfield at night. CSS only: no WebGL behind content (3D lives in its own stages).
 * Paused while the tab is hidden; reduced motion freezes it (globals.css).
 */
export function AppBackdrop() {
  const [hidden, setHidden] = useState(() => typeof document !== 'undefined' && document.hidden);
  useEffect(() => {
    const onChange = () => {
      setHidden(document.hidden);
    };
    document.addEventListener('visibilitychange', onChange);
    return () => {
      document.removeEventListener('visibilitychange', onChange);
    };
  }, []);
  return (
    <div aria-hidden="true" className="app-backdrop" data-paused={hidden ? '' : undefined}>
      {FIELDS.map((f) => (
        <span
          key={`${f.top}-${f.left}`}
          style={
            {
              background: f.color,
              width: f.size,
              height: f.size,
              top: f.top,
              left: f.left,
              '--dx': f.dx,
              '--dy': f.dy,
            } as React.CSSProperties
          }
        />
      ))}
    </div>
  );
}
