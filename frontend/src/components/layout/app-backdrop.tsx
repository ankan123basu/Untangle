import { useEffect, useState } from 'react';

const FIELDS = [
  { color: 'var(--ut-a3)', size: '52vmax', top: '-20vmax', left: '-14vmax', dx: '9vw', dy: '7vh' },
  { color: 'var(--ut-a1)', size: '40vmax', top: '38vh', left: '60vw', dx: '-8vw', dy: '-9vh' },
  { color: 'var(--ut-a2)', size: '34vmax', top: '68vh', left: '4vw', dx: '7vw', dy: '-6vh' },
  { color: 'var(--ut-a3)', size: '26vmax', top: '-6vh', left: '70vw', dx: '-6vw', dy: '8vh' },
  { color: 'var(--ut-a1)', size: '22vmax', top: '30vh', left: '22vw', dx: '5vw', dy: '6vh' },
] as const;

/**
 * The vivid gradient mesh behind the glass panels (§12.3, ADR-025), in the current colour theme.
 * CSS only: no WebGL, no per-frame JavaScript. Paused while the tab is hidden; reduced motion
 * freezes it (globals.css). The optional 3D layer (AmbientLayer) floats above it.
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
