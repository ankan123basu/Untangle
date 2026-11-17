import { useEffect, useState } from 'react';

const FIELDS = [
  {
    color: 'var(--ut-brand)',
    size: '46vmax',
    top: '-18vmax',
    left: '-12vmax',
    dx: '9vw',
    dy: '7vh',
  },
  { color: 'var(--ut-accent)', size: '38vmax', top: '40vh', left: '62vw', dx: '-8vw', dy: '-9vh' },
  { color: 'var(--ut-success)', size: '30vmax', top: '70vh', left: '8vw', dx: '7vw', dy: '-6vh' },
] as const;

/**
 * Subtle animated background behind the app's glass panels (§12.3). CSS only: no WebGL, no
 * per-frame JavaScript. Paused while the tab is hidden; reduced motion freezes it (globals.css).
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
          key={f.color}
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
