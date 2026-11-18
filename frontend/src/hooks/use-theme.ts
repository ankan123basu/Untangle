import { useCallback, useSyncExternalStore } from 'react';

/*
 * Appearance preferences, stored per browser (a convenience, not data): light/dark mode, the colour
 * theme, and whether the 3D scenes run. Applied as attributes on <html>; public/theme-init.js
 * applies the stored values before first paint, so there is no flash.
 */

export type ThemeChoice = 'system' | 'light' | 'dark';

/** The colour themes, in picker order. CSS for each lives in styles/tokens.css. */
export const ACCENTS = [
  { id: 'violet', label: 'Violet', swatch: ['#6d28d9', '#c084fc'] },
  { id: 'indigo', label: 'Indigo', swatch: ['#4338ca', '#818cf8'] },
  { id: 'ocean', label: 'Ocean', swatch: ['#1d4ed8', '#38bdf8'] },
  { id: 'teal', label: 'Teal', swatch: ['#0f766e', '#2dd4bf'] },
  { id: 'emerald', label: 'Emerald', swatch: ['#047857', '#34d399'] },
  { id: 'amber', label: 'Amber', swatch: ['#b45309', '#fbbf24'] },
  { id: 'sunset', label: 'Sunset', swatch: ['#c2410c', '#fb923c'] },
  { id: 'rose', label: 'Rose', swatch: ['#be123c', '#fb7185'] },
  { id: 'fuchsia', label: 'Fuchsia', swatch: ['#a21caf', '#e879f9'] },
  { id: 'graphite', label: 'Graphite', swatch: ['#334155', '#94a3b8'] },
] as const;
export type AccentId = (typeof ACCENTS)[number]['id'];
export const DEFAULT_ACCENT: AccentId = 'violet';

/** 'auto' = run the 3D scenes when the device can (see canRunScene3d). */
export type Scene3dChoice = 'auto' | 'off';

const THEME_KEY = 'untangle.theme';
const ACCENT_KEY = 'untangle.accent';
const SCENE_KEY = 'untangle.scene3d';

function readStorage(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    return null; // storage blocked (private mode): defaults apply
  }
}

function writeStorage(key: string, value: string | null) {
  try {
    if (value === null) localStorage.removeItem(key);
    else localStorage.setItem(key, value);
  } catch {
    /* ignore: the choice just won't persist */
  }
}

const listeners = new Set<() => void>();
const subscribe = (onChange: () => void) => {
  listeners.add(onChange);
  return () => listeners.delete(onChange);
};
const notify = () => {
  listeners.forEach((l) => {
    l();
  });
};

function readTheme(): ThemeChoice {
  const value = readStorage(THEME_KEY);
  return value === 'light' || value === 'dark' ? value : 'system';
}

function readAccent(): AccentId {
  const value = readStorage(ACCENT_KEY);
  return ACCENTS.some((a) => a.id === value) ? (value as AccentId) : DEFAULT_ACCENT;
}

function readScene(): Scene3dChoice {
  return readStorage(SCENE_KEY) === 'off' ? 'off' : 'auto';
}

/** Light / dark / follow-the-OS. */
export function useTheme(): [ThemeChoice, (next: ThemeChoice) => void] {
  const theme = useSyncExternalStore(subscribe, readTheme, () => 'system' as const);
  const setTheme = useCallback((next: ThemeChoice) => {
    writeStorage(THEME_KEY, next === 'system' ? null : next);
    if (next === 'system') document.documentElement.removeAttribute('data-theme');
    else document.documentElement.setAttribute('data-theme', next);
    notify();
  }, []);
  return [theme, setTheme];
}

/** The colour theme (accent gradient, background mesh, 3D materials). */
export function useAccent(): [AccentId, (next: AccentId) => void] {
  const accent = useSyncExternalStore(subscribe, readAccent, () => DEFAULT_ACCENT);
  const setAccent = useCallback((next: AccentId) => {
    writeStorage(ACCENT_KEY, next === DEFAULT_ACCENT ? null : next);
    document.documentElement.setAttribute('data-accent', next);
    notify();
  }, []);
  return [accent, setAccent];
}

/** Whether the person allows the 3D scenes ('auto') or switched them off. */
export function useScene3d(): [Scene3dChoice, (next: Scene3dChoice) => void] {
  const scene = useSyncExternalStore(subscribe, readScene, () => 'auto' as const);
  const setScene = useCallback((next: Scene3dChoice) => {
    writeStorage(SCENE_KEY, next === 'auto' ? null : next);
    notify();
  }, []);
  return [scene, setScene];
}

const DARK_QUERY = '(prefers-color-scheme: dark)';

/** The theme actually on screen: the user's choice, or the OS setting for "system". */
export function useIsDark(): boolean {
  const [theme] = useTheme();
  const systemDark = useSyncExternalStore(
    (onChange) => {
      const query = window.matchMedia(DARK_QUERY);
      query.addEventListener('change', onChange);
      return () => {
        query.removeEventListener('change', onChange);
      };
    },
    () => window.matchMedia(DARK_QUERY).matches,
    () => false,
  );
  return theme === 'dark' || (theme === 'system' && systemDark);
}

/** True when the user asked the OS for less motion (we also honour it in CSS). */
export function prefersReducedMotion(): boolean {
  return (
    typeof window !== 'undefined' && window.matchMedia('(prefers-reduced-motion: reduce)').matches
  );
}
