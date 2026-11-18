import { Component, lazy, Suspense, useCallback, useRef, useState, type ReactNode } from 'react';
import { useNavigate } from 'react-router';
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

type Select = (priority: Priority) => void;
type Hover = (priority: Priority | null) => void;

const LABEL: Record<Priority, string> = {
  CRITICAL: 'Critical',
  HIGH: 'High',
  MEDIUM: 'Medium',
  LOW: 'Low',
};

/** The same stacks in flat CSS, with the same hover and click. */
function FlatStacks({
  counts,
  hovered,
  onHover,
  onSelect,
}: {
  counts: Partial<Record<Priority, number>> | undefined;
  hovered: Priority | null;
  onHover: Hover;
  onSelect: Select;
}) {
  return (
    <div className="absolute inset-x-4 top-10 bottom-9 flex items-end justify-around gap-3">
      {PRIORITIES.map((p) => {
        const n = Math.min(counts?.[p] ?? 0, 7);
        return (
          <div
            key={p}
            data-priority={p}
            onMouseEnter={() => {
              onHover(p);
            }}
            onMouseLeave={() => {
              onHover(null);
            }}
            onClick={() => {
              onSelect(p);
            }}
            className={cn(
              'flex w-14 cursor-pointer flex-col-reverse items-center gap-1 pt-2 transition-transform duration-150',
              hovered === p && '-translate-y-1.5 scale-105',
            )}
          >
            <span
              className={cn(
                'h-2 w-14 rounded-sm border-2 border-[#111]',
                hovered === p ? FLAT[p] : 'bg-white',
              )}
            />
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
 * Hover a stack to see its count, click it to open that priority's unresolved tickets, drag to
 * turn the set (it springs back). Pointer-only and hidden from screen readers: the priority
 * chips beside it are the keyboard and screen-reader path to the same lists. Runs only on capable
 * devices with 3D on, once visible and after the numbers; otherwise flat CSS stacks that behave
 * the same way.
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
  const navigate = useNavigate();
  const show3d = capable && choice !== 'off';
  const [hovered, setHovered] = useState<Priority | null>(null);
  const [dragging, setDragging] = useState(false);
  const yaw = useRef(0);
  const drag = useRef({ x: 0, start: 0, moved: false });

  const select = useCallback<Select>(
    (p) => {
      if (drag.current.moved) return; // the end of a drag is not a click
      void navigate(`/tickets?priority=${p}&status=OPEN&status=IN_PROGRESS`);
    },
    [navigate],
  );

  const endDrag = () => {
    setDragging(false);
    yaw.current = 0; // spring back to the front view
  };

  const flat = (
    <FlatStacks counts={counts} hovered={hovered} onHover={setHovered} onSelect={select} />
  );
  const n = hovered ? (counts?.[hovered] ?? 0) : 0;

  return (
    <div
      aria-hidden="true"
      data-testid="ticket-stacks"
      onPointerDown={(e) => {
        drag.current = { x: e.clientX, start: yaw.current, moved: false };
        setDragging(true);
      }}
      onPointerMove={(e) => {
        if (!dragging) return;
        const dx = e.clientX - drag.current.x;
        if (Math.abs(dx) > 5) drag.current.moved = true;
        yaw.current = Math.max(-0.75, Math.min(0.75, drag.current.start + dx * 0.008));
      }}
      onPointerUp={endDrag}
      onPointerCancel={endDrag}
      onPointerLeave={() => {
        endDrag();
        setHovered(null);
      }}
      className={cn(
        'relative touch-pan-y overflow-hidden rounded-md border-[3px] border-line bg-surface shadow-hard-sm select-none',
        dragging ? 'cursor-grabbing' : hovered ? 'cursor-pointer' : 'cursor-grab',
        className,
      )}
    >
      {show3d && counts ? (
        <WhenVisible fallback={flat} delay={500}>
          <SceneBoundary fallback={flat}>
            <Suspense fallback={flat}>
              <TicketStacksScene
                counts={counts}
                shadowColor={shadow}
                hovered={hovered}
                onHover={setHovered}
                onSelect={select}
                yaw={yaw}
              />
            </Suspense>
          </SceneBoundary>
        </WhenVisible>
      ) : (
        flat
      )}
      {/* One label slot, top-left: what you're pointing at, or how to use the stage. */}
      <p className="pointer-events-none absolute top-2 left-2 rounded-md border-2 border-line bg-surface px-2 py-0.5 text-[11px] font-extrabold text-ink shadow-hard-sm">
        {hovered ? (
          <>
            {LABEL[hovered]} · {n} open · <span className="underline">click to view</span>
          </>
        ) : (
          'Hover · click a stack · drag to turn'
        )}
      </p>
      <div className="pointer-events-none absolute inset-x-0 bottom-0 flex justify-around border-t-2 border-line bg-surface py-1 text-[10px] font-extrabold tracking-wider text-ink uppercase">
        {PRIORITIES.map((p) => (
          <span
            key={p}
            className={cn('rounded-sm px-1', hovered === p && 'bg-brand text-on-brand')}
          >
            {p === 'CRITICAL' ? 'Crit' : p.toLowerCase()}
          </span>
        ))}
      </div>
    </div>
  );
}
