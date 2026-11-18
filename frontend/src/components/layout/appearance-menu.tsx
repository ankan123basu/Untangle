import { useEffect, useId, useRef, useState, type ReactNode } from 'react';
import { Box, Check, Monitor, Moon, Palette, Sun } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';
import { ACCENTS, useAccent, useScene3d, useTheme, type ThemeChoice } from '@/hooks/use-theme';

const MODES: { value: ThemeChoice; label: string; icon: ReactNode }[] = [
  { value: 'light', label: 'Light', icon: <Sun className="size-4" /> },
  { value: 'dark', label: 'Dark', icon: <Moon className="size-4" /> },
  { value: 'system', label: 'System', icon: <Monitor className="size-4" /> },
];

/**
 * Appearance: light/dark mode, sixteen colour themes and the 3D scenes switch (ADR-027). Used in the
 * app header and on the login page. Choices are remembered per browser.
 */
export function AppearanceMenu({
  className,
  triggerClassName,
}: {
  className?: string;
  /** Extra classes for the palette button, e.g. light-on-dark on the login stage. */
  triggerClassName?: string;
}) {
  const [open, setOpen] = useState(false);
  const [theme, setTheme] = useTheme();
  const [accent, setAccent] = useAccent();
  const [scene, setScene] = useScene3d();
  const root = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const panelId = useId();
  const accentName = useId();

  useEffect(() => {
    if (!open) return;
    const onPointer = (e: PointerEvent) => {
      if (root.current && !root.current.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setOpen(false);
        trigger.current?.focus();
      }
    };
    document.addEventListener('pointerdown', onPointer);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('pointerdown', onPointer);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  const current = ACCENTS.find((a) => a.id === accent) ?? ACCENTS[0];

  return (
    <div ref={root} className={cn('relative', className)}>
      <Button
        ref={trigger}
        variant="ghost"
        size="icon"
        className={triggerClassName}
        aria-label="Appearance"
        aria-expanded={open}
        aria-controls={panelId}
        title="Appearance: mode, colours and 3D"
        onClick={() => {
          setOpen((o) => !o);
        }}
      >
        <Palette className="size-5" />
      </Button>
      {open && (
        <div
          id={panelId}
          role="dialog"
          aria-label="Appearance"
          className="solid-panel absolute top-full right-0 z-50 mt-2 w-[min(88vw,20rem)] animate-rise p-4 text-ink"
        >
          <p className="text-xs font-bold tracking-wider text-muted uppercase">Mode</p>
          <div className="mt-2 grid grid-cols-3 gap-1 rounded-md border-2 border-line bg-paper-2 p-1">
            {MODES.map((m) => (
              <button
                key={m.value}
                type="button"
                aria-pressed={theme === m.value}
                onClick={() => {
                  setTheme(m.value);
                }}
                className={cn(
                  'flex items-center justify-center gap-1.5 rounded-sm border-2 px-2 py-1.5 text-sm font-bold transition-colors',
                  theme === m.value
                    ? 'border-line bg-brand text-on-brand shadow-hard-sm'
                    : 'border-transparent text-muted hover:text-ink',
                )}
              >
                {m.icon}
                {m.label}
              </button>
            ))}
          </div>

          <fieldset className="mt-5">
            <legend className="text-xs font-bold tracking-wider text-muted uppercase">
              Colour theme · <span className="text-ink normal-case">{current.label}</span>
            </legend>
            <div className="mt-2 grid grid-cols-6 gap-2.5">
              {ACCENTS.map((a) => (
                <label key={a.id} className="group relative flex justify-center" title={a.label}>
                  <input
                    type="radio"
                    name={accentName}
                    value={a.id}
                    checked={accent === a.id}
                    onChange={() => {
                      setAccent(a.id);
                    }}
                    className="peer sr-only"
                    aria-label={a.label}
                  />
                  <span
                    aria-hidden="true"
                    style={{ backgroundColor: a.swatch }}
                    className={cn(
                      'flex size-10 cursor-pointer items-center justify-center rounded-md border-2 border-[#111] text-[#111] shadow-[3px_3px_0_0_#111] transition-transform group-hover:-translate-y-0.5 group-hover:rotate-[-4deg]',
                      'peer-focus-visible:outline-3 peer-focus-visible:outline-offset-2 peer-focus-visible:outline-accent',
                      accent === a.id && 'translate-x-[2px] translate-y-[2px] shadow-none',
                    )}
                  >
                    {accent === a.id && <Check className="size-4" strokeWidth={3} />}
                  </span>
                </label>
              ))}
            </div>
          </fieldset>

          <div className="mt-5 flex items-center justify-between gap-3 border-t-2 border-line-soft pt-4">
            <span className="flex items-center gap-2 text-sm font-semibold">
              <Box aria-hidden="true" className="size-4" />
              3D scenes
            </span>
            <button
              type="button"
              role="switch"
              aria-checked={scene === 'auto'}
              aria-label="3D scenes"
              onClick={() => {
                setScene(scene === 'auto' ? 'off' : 'auto');
              }}
              className={cn(
                'relative h-7 w-12 rounded-full border-2 border-line transition-colors',
                scene === 'auto' ? 'bg-brand' : 'bg-paper-2',
              )}
            >
              <span
                className={cn(
                  'absolute top-0.5 left-0.5 size-5 rounded-full border-2 border-line bg-white transition-transform',
                  scene === 'auto' && 'translate-x-5',
                )}
              />
            </button>
          </div>
          <p className="mt-1.5 text-xs text-muted">
            The login scene and the dashboard ticket stacks. Only on capable devices, never with
            reduced motion.
          </p>
        </div>
      )}
    </div>
  );
}
