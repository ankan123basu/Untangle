import { useEffect, useRef } from 'react';
import { useNavigate } from 'react-router';

function isTyping(target: EventTarget | null): boolean {
  return (
    target instanceof HTMLElement &&
    (target.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName))
  );
}

/**
 * Global keyboard shortcuts (§12.5): Ctrl/⌘+K toggles the palette; outside text fields `/` opens
 * it, `c` creates a ticket, `g d` goes to the dashboard and `g t` to the tickets list.
 */
export function useGlobalShortcuts(paletteOpen: boolean, setPaletteOpen: (open: boolean) => void) {
  const navigate = useNavigate();
  const pendingG = useRef(false);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        setPaletteOpen(!paletteOpen);
        return;
      }
      if (paletteOpen || isTyping(event.target) || event.metaKey || event.ctrlKey || event.altKey)
        return;
      if (event.key === '/') {
        event.preventDefault();
        setPaletteOpen(true);
      } else if (event.key === 'c') {
        void navigate('/tickets/new');
      } else if (event.key === 'g') {
        pendingG.current = true;
        window.setTimeout(() => (pendingG.current = false), 800);
      } else if (pendingG.current && event.key === 'd') {
        void navigate('/');
      } else if (pendingG.current && event.key === 't') {
        void navigate('/tickets');
      }
    };
    window.addEventListener('keydown', onKey);
    return () => {
      window.removeEventListener('keydown', onKey);
    };
  }, [paletteOpen, setPaletteOpen, navigate]);
}
