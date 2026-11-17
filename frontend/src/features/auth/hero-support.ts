import { prefersReducedMotion } from '@/hooks/use-theme';

/**
 * Should the 3D scene run? Not without WebGL, not on low-power or data-saving devices, and not
 * when the user asked for reduced motion (§12.3). Then the static artwork below is the hero.
 */
export function canRunHeroScene(): boolean {
  if (typeof window === 'undefined' || prefersReducedMotion()) return false;
  const nav = navigator as Navigator & { connection?: { saveData?: boolean } };
  if (nav.connection?.saveData) return false;
  if (nav.hardwareConcurrency > 0 && nav.hardwareConcurrency < 4) return false;
  if (window.innerWidth < 1024) return false; // the hero panel is hidden below lg anyway
  try {
    const canvas = document.createElement('canvas');
    return Boolean(canvas.getContext('webgl2') ?? canvas.getContext('webgl'));
  } catch {
    return false;
  }
}
