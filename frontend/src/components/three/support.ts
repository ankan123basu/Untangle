import { useMemo } from 'react';
import { prefersReducedMotion, useAccent, useIsDark } from '@/hooks/use-theme';

/**
 * Can this device run a 3D scene? Not without WebGL, not on low-power or data-saving devices, not
 * below `minWidth` pixels, and not when the user asked for reduced motion (§12.3, ADR-024). When
 * this says no, the CSS gradient mesh is the whole background and the static art is the hero.
 */
export function canRunScene3d(minWidth: number): boolean {
  if (typeof window === 'undefined' || prefersReducedMotion()) return false;
  const nav = navigator as Navigator & { connection?: { saveData?: boolean } };
  if (nav.connection?.saveData) return false;
  if (nav.hardwareConcurrency > 0 && nav.hardwareConcurrency < 4) return false;
  if (window.innerWidth < minWidth) return false;
  try {
    const canvas = document.createElement('canvas');
    return Boolean(canvas.getContext('webgl2') ?? canvas.getContext('webgl'));
  } catch {
    return false;
  }
}

/**
 * The colour 3D hard shadows should use, matching the 2D ones: ink by day, the theme's pop colour
 * by night (black on near-black would vanish). Re-read when the theme or mode changes.
 */
export function useShadowColor(): string {
  const [accent] = useAccent();
  const dark = useIsDark();
  return useMemo(() => {
    if (!dark || typeof document === 'undefined') return '#111111';
    const pop = getComputedStyle(document.documentElement).getPropertyValue('--ut-pop').trim();
    return /^#[0-9a-f]{6}$/i.test(pop) ? pop : '#ffd23f';
    // accent is a dependency on purpose: the CSS variable changes with it.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [accent, dark]);
}
