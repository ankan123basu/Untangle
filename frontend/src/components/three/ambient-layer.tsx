import { Component, lazy, Suspense, useEffect, useState, type ReactNode } from 'react';
import { useScene3d } from '@/hooks/use-theme';
import { canRunScene3d } from './support';
import { useThemeColors } from './theme-colors';

// three.js and R3F stay out of the main bundle (ADR-024): this chunk loads only when it will run.
const AmbientScene = lazy(() => import('./ambient-scene'));

/** If WebGL fails at runtime (context lost, driver bug), quietly keep the CSS background. */
class SceneBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  override state = { failed: false };
  static getDerivedStateFromError() {
    return { failed: true };
  }
  override render() {
    return this.state.failed ? null : this.props.children;
  }
}

/**
 * Resolves once the page has loaded and the browser has been idle for a moment, so downloading
 * three.js and compiling shaders never competes with the first screen (Lighthouse TBT, ADR-024).
 */
function useAfterIdle(enabled: boolean, extraDelayMs = 1200): boolean {
  const [ready, setReady] = useState(false);
  useEffect(() => {
    if (!enabled || ready) return;
    let timer: number | undefined;
    let idle: number | undefined;
    const schedule = () => {
      const go = () => {
        timer = window.setTimeout(() => {
          setReady(true);
        }, extraDelayMs);
      };
      if ('requestIdleCallback' in window) idle = window.requestIdleCallback(go, { timeout: 4000 });
      else go();
    };
    if (document.readyState === 'complete') schedule();
    else window.addEventListener('load', schedule, { once: true });
    return () => {
      window.removeEventListener('load', schedule);
      window.clearTimeout(timer);
      if (idle !== undefined) window.cancelIdleCallback(idle);
    };
  }, [enabled, ready, extraDelayMs]);
  return ready;
}

/**
 * The optional 3D layer between the gradient mesh and the glass panels. Runs only when the device
 * can (canRunScene3d) and the person hasn't switched 3D off in Appearance, and starts only after
 * the page is idle; it fades in over the CSS mesh, which is the background until then.
 */
export function AmbientLayer() {
  const [capable] = useState(() => canRunScene3d(768));
  const [choice] = useScene3d();
  const colors = useThemeColors();
  const ready = useAfterIdle(capable && choice !== 'off');
  if (!capable || choice === 'off' || !ready) return null;
  return (
    <SceneBoundary>
      <Suspense fallback={null}>
        <AmbientScene colors={colors} />
      </Suspense>
    </SceneBoundary>
  );
}
