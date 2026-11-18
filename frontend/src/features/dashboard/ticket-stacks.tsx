import { Component, lazy, Suspense, useState, type ReactNode } from 'react';
import { canRunScene3d, useShadowColor } from '@/components/three/support';
import { WhenVisible } from '@/components/ui/when-visible';
import { useScene3d } from '@/hooks/use-theme';
import { PRIORITIES, type Priority } from '@/lib/api/types';
import { cn } from '@/lib/utils';

// three.js stays out of the dashboard's main chunk (ADR-024): loaded only when the stacks run.
const TicketStacksScene = lazy(() => import('./ticket-stacks-scene'));

const FLAT: Record<Priority, string> = {
  CRITICAL: 'bg-[#ff5a5f]',
  HIGH: 'bg-[#ffb547]',
  MEDIUM: 'bg-[#5cc8ff]',
  LOW: 'bg-[#d6d3cc]',
};

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

/** The same stacks in flat CSS: one bar of slabs per priority. */
function FlatStacks({ counts }: { counts: Partial<Record<Priority, number>> | undefined }) {
  return (
    <div className="absolute inset-x-4 top-4 bottom-9 flex items-end justify-around gap-3">
      {PRIORITIES.map((p) => {
        const n = Math.min(counts?.[p] ?? 0, 7);
        return (
          <div key={p} className="flex w-12 flex-col-reverse items-center gap-1">
            <span className="h-2 w-14 rounded-sm border-2 border-[#111] bg-white" />
            {Array.from({ length: n }, (_, i) => (
              <span
                key={i}
                className={cn('h-4 w-12 rounded-sm border-2 border-[#111]', FLAT[p])}
                style={{ transform: `rotate(${String(i % 2 === 0 ? 2 : -2)}deg)` }}
              />
            ))}
          </div>
        );
      })}
    </div>
  );
}

/**
 * The dashboard's 3D stage: the unresolved queue as real stacks of tickets per priority.
 * Decorative (the exact numbers are beside it), so hidden from screen readers. Runs only on
 * capable devices with 3D on, once visible and after the numbers; otherwise flat CSS stacks.
 */
export function TicketStacks({
  counts,
  className,
}: {
  counts: Partial<Record<Priority, number>> | undefined;
  className?: string;
}) {
  const [capable] = useState(() => canRunScene3d(1024));
  const [choice] = useScene3d();
  const shadow = useShadowColor();
  const show3d = capable && choice !== 'off';
  const flat = <FlatStacks counts={counts} />;
  return (
    <div
      aria-hidden="true"
      className={cn(
        'relative overflow-hidden rounded-md border-[3px] border-line bg-surface shadow-hard-sm',
        className,
      )}
    >
      {show3d && counts ? (
        <WhenVisible fallback={flat} delay={500}>
          <SceneBoundary fallback={flat}>
            <Suspense fallback={flat}>
              <TicketStacksScene counts={counts} shadowColor={shadow} />
            </Suspense>
          </SceneBoundary>
        </WhenVisible>
      ) : (
        flat
      )}
      <div className="absolute inset-x-0 bottom-0 flex justify-around border-t-2 border-line bg-surface py-1 text-[10px] font-extrabold tracking-wider text-ink uppercase">
        {PRIORITIES.map((p) => (
          <span key={p}>{p === 'CRITICAL' ? 'Crit' : p.toLowerCase()}</span>
        ))}
      </div>
    </div>
  );
}
