import { afterEach, describe, expect, it, vi } from 'vitest';
import { canRunHeroScene } from './hero-support';

function mockReducedMotion(reduce: boolean) {
  vi.spyOn(window, 'matchMedia').mockImplementation(
    (query: string) =>
      ({
        matches: reduce && query.includes('reduce'),
        media: query,
        addEventListener: () => undefined,
        removeEventListener: () => undefined,
      }) as unknown as MediaQueryList,
  );
}

function mockWebGl(available: boolean) {
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockImplementation(
    () => (available ? {} : null) as never,
  );
}

describe('3D hero gate (§12.3 fallbacks)', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    Object.defineProperty(window, 'innerWidth', { value: 1024, configurable: true });
  });

  it('runs on a capable desktop', () => {
    mockReducedMotion(false);
    mockWebGl(true);
    Object.defineProperty(window, 'innerWidth', { value: 1440, configurable: true });
    expect(canRunHeroScene()).toBe(true);
  });

  it('falls back to the static hero when the user prefers reduced motion', () => {
    mockReducedMotion(true);
    mockWebGl(true);
    Object.defineProperty(window, 'innerWidth', { value: 1440, configurable: true });
    expect(canRunHeroScene()).toBe(false);
  });

  it('falls back without WebGL', () => {
    mockReducedMotion(false);
    mockWebGl(false);
    Object.defineProperty(window, 'innerWidth', { value: 1440, configurable: true });
    expect(canRunHeroScene()).toBe(false);
  });

  it('skips the scene on small screens, where the hero panel is hidden', () => {
    mockReducedMotion(false);
    mockWebGl(true);
    Object.defineProperty(window, 'innerWidth', { value: 800, configurable: true });
    expect(canRunHeroScene()).toBe(false);
  });
});
