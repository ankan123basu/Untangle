import type { ReactNode } from 'react';
import { Monitor, Moon, Sun } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { useTheme, type ThemeChoice } from '@/hooks/use-theme';

const THEMES: { value: ThemeChoice; icon: ReactNode; label: string }[] = [
  { value: 'light', icon: <Sun className="size-4" />, label: 'Light theme' },
  { value: 'dark', icon: <Moon className="size-4" />, label: 'Dark theme' },
  { value: 'system', icon: <Monitor className="size-4" />, label: 'Follow system theme' },
];

/** Cycles light → dark → follow the system. Used in the app header and on the login page. */
export function ThemeToggle({ className }: { className?: string }) {
  const [theme, setTheme] = useTheme();
  const index = THEMES.findIndex((t) => t.value === theme);
  const current = THEMES[index] ?? THEMES[2];
  const next = THEMES[(index + 1) % THEMES.length] ?? THEMES[0];
  if (!current || !next) return null;
  return (
    <Button
      variant="ghost"
      size="icon"
      {...(className ? { className } : {})}
      aria-label={`${current.label}. Switch to ${next.label.toLowerCase()}`}
      title={current.label}
      onClick={() => {
        setTheme(next.value);
      }}
    >
      {current.icon}
    </Button>
  );
}
