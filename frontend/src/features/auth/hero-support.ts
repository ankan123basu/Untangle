import { canRunScene3d } from '@/components/three/support';

/**
 * Should the login 3D scene run? Same rules as every 3D scene (see canRunScene3d); the hero panel
 * only shows from the lg breakpoint, so smaller screens never load it.
 */
export function canRunHeroScene(): boolean {
  return canRunScene3d(1024);
}
