import { Component, lazy, Suspense, useState, type ReactNode } from 'react';
import { canRunScene3d } from '@/components/three/support';
import { useThemeColors } from '@/components/three/theme-colors';
import { WhenVisible } from '@/components/ui/when-visible';
import { useScene3d } from '@/hooks/use-theme';
import type { Priority } from '@/lib/api/types';
import { cn } from '@/lib/utils';

// three.js stays out of the dashboard's main chunk (ADR-024): loaded only when the orbit will run.
const QueueOrbitScene = lazy(() => import('./queue-orbit-scene'));

const RINGS = [
  { priority: 'CRITICAL', color: '#f43f5e', size: '42%' },
  { priority: 'HIGH', color: '#f59e0b', size: '60%' },
  { priority: 'MEDIUM', color: '#60a5fa', size: '78%' },
  { priority: 'LOW', color: '#a3b1c6', size: '96%' },
] as const;

class SceneBoundary extends Component<
  { children: ReactNode; fallback: ReactNode },
  { failed: boolean }
> {
  override state = { failed: false };
  static getDerivedStateFromError() {
    return { failed: true };
  }
  override render() {
    return this.state.failed ? this.props.fallback : this.props.children;
  }
}

/** The same picture without WebGL: flat orbits around a glowing core. */
function StaticOrbit() {
  return (
    <div className="absolute inset-0 flex items-center justify-center">
      <div className="relative aspect-square h-[88%]">
        {RINGS.map((r) => (
          <span
            key={r.priority}
            className="absolute top-1/2 left-1/2 -translate-1/2 rounded-full border"
            style={{ width: r.size, height: r.size, borderColor: `${r.color}88` }}
          />
        ))}
        <span className="absolute top-1/2 left-1/2 size-[24%] -translate-1/2 rounded-full bg-brand-gradient shadow-[0_0_40px_-4px_var(--ut-a3)]" />
      </div>
    </div>
  );
}

/**
 * The dashboard's 3D stage: the unresolved queue orbiting a glass core, one ring per priority.
 * Decorative (the exact numbers sit beside it), so it is hidden from screen readers. Runs only on
 * capable devices with 3D switched on, after the numbers are on screen; otherwise a static version.
 */
export function QueueOrbit({
  counts,
  className,
}: {
  counts: Partial<Record<Priority, number>> | undefined;
  className?: string;
}) {
  const [capable] = useState(() => canRunScene3d(1024));
  const [choice] = useScene3d();
  const colors = useThemeColors();
  const show3d = capable && choice !== 'off';
  return (
    <div
      aria-hidden="true"
      className={cn(
        'relative overflow-hidden rounded-2xl border border-line-soft bg-[radial-gradient(circle_at_50%_45%,color-mix(in_oklab,var(--ut-a3)_22%,transparent),transparent_70%)]',
        className,
      )}
    >
      {show3d && counts ? (
        <WhenVisible fallback={<StaticOrbit />} delay={600}>
          <SceneBoundary fallback={<StaticOrbit />}>
            <Suspense fallback={<StaticOrbit />}>
              <QueueOrbitScene colors={colors} counts={counts} />
            </Suspense>
          </SceneBoundary>
        </WhenVisible>
      ) : (
        <StaticOrbit />
      )}
      <span className="absolute bottom-2 left-3 text-[11px] font-semibold tracking-wider text-muted uppercase">
        Live queue
      </span>
    </div>
  );
}
