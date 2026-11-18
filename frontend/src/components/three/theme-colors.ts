import { useMemo } from 'react';
import { useAccent, useIsDark } from '@/hooks/use-theme';

/** The current colour theme's three accent colours, read from the CSS tokens (tokens.css). */
export interface ThemeColors {
  a1: string;
  a2: string;
  a3: string;
  dark: boolean;
}

const FALLBACK = { a1: '#6d28d9', a2: '#9333ea', a3: '#c084fc' };

function readVar(style: CSSStyleDeclaration, name: string, fallback: string): string {
  const value = style.getPropertyValue(name).trim();
  return /^#[0-9a-f]{3,8}$/i.test(value) ? value : fallback;
}

/**
 * WebGL materials can't use CSS variables, so 3D scenes read the accent colours here. Recomputed
 * when the colour theme or light/dark mode changes (both attributes are set before listeners run).
 */
export function useThemeColors(): ThemeColors {
  const [accent] = useAccent();
  const dark = useIsDark();
  return useMemo(() => {
    if (typeof document === 'undefined') return { ...FALLBACK, dark };
    const style = getComputedStyle(document.documentElement);
    return {
      a1: readVar(style, '--ut-a1', FALLBACK.a1),
      a2: readVar(style, '--ut-a2', FALLBACK.a2),
      a3: readVar(style, '--ut-a3', FALLBACK.a3),
      dark,
    };
    // accent is a dependency on purpose: the CSS variables change with it.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [accent, dark]);
}
