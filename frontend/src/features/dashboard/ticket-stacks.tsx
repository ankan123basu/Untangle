import {
  Component,
  lazy,
  Suspense,
  useCallback,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { useNavigate } from 'react-router';
import { useQueries } from '@tanstack/react-query';
import { canRunScene3d, useShadowColor } from '@/components/three/support';
import { useScene3d } from '@/hooks/use-theme';
import { api } from '@/lib/api/client';
import { PRIORITIES, type Priority, type TicketPage } from '@/lib/api/types';
import { queryKeys } from '@/lib/query-client';
import { cn } from '@/lib/utils';
import { LIVE } from '@/features/tickets/ticket-api';
import type { Slab, StackData, Target } from './ticket-stacks-model';
import { sameTarget, SLABS_PER_STACK } from './ticket-stacks-model';

// three.js stays out of the dashboard's main chunk (ADR-024). The chunk is requested as soon as
// the dashboard mounts (not after the page settles), so the stage appears without a wait.
const loadScene = () => import('./ticket-stacks-scene');
const TicketStacksScene = lazy(loadScene);

const FLAT: Record<Priority, string> = {
  CRITICAL: 'bg-[#ff5a5f]',
  HIGH: 'bg-[#ffb547]',
  MEDIUM: 'bg-[#5cc8ff]',
  LOW: 'bg-[#d6d3cc]',
};

const LABEL: Record<Priority, string> = {
  CRITICAL: 'Critical',
  HIGH: 'High',
  MEDIUM: 'Medium',
  LOW: 'Low',
};

const UNRESOLVED = ['OPEN', 'IN_PROGRESS'];

/**
 * The most urgent unresolved tickets per priority (SLA due soonest first), live like the lists.
 * Uses the ticket list's query keys, so any ticket change invalidates these too.
 */
function useStacks(): StackData[] | undefined {
  const results = useQueries({
    queries: PRIORITIES.map((priority) => {
      const params = {
        priority,
        status: UNRESOLVED,
        sort: 'slaDueAt,asc',
        size: SLABS_PER_STACK,
        page: 0,
      };
      return {
        queryKey: queryKeys.tickets.list({ ...params, view: 'stacks' }),
        queryFn: async ({ signal }: { signal: AbortSignal }) =>
          (
            await api.get<TicketPage>('/tickets', {
              params,
              signal,
              paramsSerializer: { indexes: null },
            })
          ).data,
        refetchInterval: LIVE.list,
      };
    }),
  });
  if (results.some((r) => !r.data)) return undefined;
  return PRIORITIES.map((priority, i) => {
    const page = results[i]?.data;
    return {
      priority,
      total: page?.totalElements ?? 0,
      slabs: (page?.content ?? []).map((t) => ({
        id: t.id,
        number: t.ticketNumber,
        subject: t.subject,
        assignee: t.assignee?.fullName ?? null,
        breached: t.slaBreached,
        slaDueAt: t.slaDueAt,
      })),
    };
  });
}

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

/** The same stacks in flat CSS: one slab per ticket, with the same hover and click. */
function FlatStacks({
  stacks,
  target,
  onHover,
  onSelect,
}: {
  stacks: StackData[] | undefined;
  target: Target | null;
  onHover: (t: Target | null) => void;
  onSelect: (t: Target) => void;
}) {
  return (
    <div className="absolute inset-x-3 top-10 bottom-8 flex items-end justify-around gap-2">
      {PRIORITIES.map((p) => {
        const stack = stacks?.find((s) => s.priority === p);
        const rest = stack ? stack.total - stack.slabs.length : 0;
        const base: Target = { kind: 'stack', priority: p };
        return (
          <div
            key={p}
            data-priority={p}
            className="flex w-14 flex-col-reverse items-center gap-0.5"
          >
            <span
              data-target="base"
              onMouseEnter={() => {
                onHover(base);
              }}
              onClick={() => {
                onSelect(base);
              }}
              className={cn(
                'h-2.5 w-14 cursor-pointer rounded-sm border-2 border-[#111]',
                target?.priority === p ? FLAT[p] : 'bg-white',
              )}
            />
            {stack?.slabs.map((slab, i) => {
              const t: Target = { kind: 'ticket', priority: p, slab };
              const on = sameTarget(target, t);
              return (
                <span
                  key={slab.id}
                  data-ticket={slab.number}
                  onMouseEnter={() => {
                    onHover(t);
                  }}
                  onClick={() => {
                    onSelect(t);
                  }}
                  className={cn(
                    'h-3 w-12 shrink-0 cursor-pointer rounded-[3px] border-2 border-[#111] transition-transform duration-100',
                    FLAT[p],
                    on && 'z-10 -translate-y-0.5 scale-x-110',
                  )}
                  style={on ? undefined : { rotate: `${String(i % 2 === 0 ? 2 : -2)}deg` }}
                />
              );
            })}
            {rest > 0 && (
              <span
                data-target="more"
                onMouseEnter={() => {
                  onHover({ kind: 'more', priority: p, rest });
                }}
                onClick={() => {
                  onSelect({ kind: 'more', priority: p, rest });
                }}
                className="w-12 cursor-pointer rounded-[3px] border-2 border-[#111] bg-[#111] text-center text-[9px] leading-3 font-extrabold text-white"
              >
                +{rest}
              </span>
            )}
          </div>
        );
      })}
    </div>
  );
}

function slaText(slab: Slab): string {
  if (slab.breached) return 'SLA breached';
  return new Date(slab.slaDueAt).getTime() > Date.now() ? 'within SLA' : 'due now';
}

/** One label slot across the top: what the pointer is on, or how to use the stage. */
function Readout({ target, stacks }: { target: Target | null; stacks: StackData[] | undefined }) {
  let text: ReactNode = 'Hover a ticket · click to open · drag to turn';
  if (target?.kind === 'ticket') {
    const s = target.slab;
    // Number and the who/when always show; only the subject shortens in a narrow stage.
    text = (
      <span className="flex min-w-0 gap-1">
        <span className="shrink-0 font-mono">{s.number}</span>
        <span className="min-w-0 truncate">· {s.subject}</span>
        <span className="shrink-0">
          · <span className="font-extrabold">{s.assignee ?? 'Unassigned'}</span> ·{' '}
          <span className={s.breached ? 'text-danger' : undefined}>{slaText(s)}</span>
        </span>
      </span>
    );
  } else if (target?.kind === 'more') {
    text = (
      <>
        +{target.rest} more {LABEL[target.priority].toLowerCase()} ·{' '}
        <span className="underline">click to see all</span>
      </>
    );
  } else if (target?.kind === 'stack') {
    const total = stacks?.find((s) => s.priority === target.priority)?.total ?? 0;
    text = (
      <>
        {LABEL[target.priority]} · {total} open · <span className="underline">click to view</span>
      </>
    );
  }
  return (
    <p
      data-testid="stacks-readout"
      className="pointer-events-none absolute top-2 right-2 left-2 truncate rounded-md border-2 border-line bg-surface px-2 py-0.5 text-[11px] font-bold text-ink shadow-hard-sm"
    >
      {text}
    </p>
  );
}

/**
 * The dashboard's 3D stage: the unresolved queue as stacks of real tickets, one stack per
 * priority, one slab per ticket (the most urgent; the rest are a "+N more" cap). Hover a slab to
 * read that ticket, click it to open it; hover a base or the cap and click for the whole list;
 * drag to turn the set (it springs back). Pointer-only and hidden from screen readers: the
 * priority chips and the queue below are the keyboard path to the same tickets. Runs only on
 * capable devices with 3D on; otherwise flat CSS stacks that behave the same way.
 */
export function TicketStacks({ className }: { className?: string }) {
  const [capable] = useState(() => canRunScene3d(1024));
  const [choice] = useScene3d();
  const shadow = useShadowColor();
  const navigate = useNavigate();
  const stacks = useStacks();
  const show3d = capable && choice !== 'off';
  const [target, setTarget] = useState<Target | null>(null);
  const [dragging, setDragging] = useState(false);
  const yaw = useRef(0);
  const drag = useRef({ x: 0, start: 0, moved: false });

  // Start downloading the scene right away instead of waiting for the lazy child to render.
  useEffect(() => {
    if (show3d) void loadScene();
  }, [show3d]);

  const hover = useCallback((t: Target | null) => {
    setTarget((prev) => (sameTarget(prev, t) ? prev : t));
  }, []);

  const select = useCallback(
    (t: Target) => {
      if (drag.current.moved) return; // the end of a drag is not a click
      if (t.kind === 'ticket') void navigate(`/tickets/${t.slab.id}`);
      else void navigate(`/tickets?priority=${t.priority}&status=OPEN&status=IN_PROGRESS`);
    },
    [navigate],
  );

  const endDrag = () => {
    setDragging(false);
    yaw.current = 0; // spring back to the front view
  };

  const flat = <FlatStacks stacks={stacks} target={target} onHover={hover} onSelect={select} />;

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
        setTarget(null);
      }}
      className={cn(
        'relative touch-pan-y overflow-hidden rounded-md border-[3px] border-line bg-surface shadow-hard-sm select-none',
        dragging ? 'cursor-grabbing' : target ? 'cursor-pointer' : 'cursor-grab',
        className,
      )}
    >
      {show3d ? (
        <SceneBoundary fallback={flat}>
          <Suspense fallback={flat}>
            <TicketStacksScene
              stacks={stacks ?? []}
              shadowColor={shadow}
              target={target}
              onHover={hover}
              onSelect={select}
              yaw={yaw}
            />
          </Suspense>
        </SceneBoundary>
      ) : (
        flat
      )}
      <Readout target={target} stacks={stacks} />
      <div className="pointer-events-none absolute inset-x-0 bottom-0 flex justify-around border-t-2 border-line bg-surface py-1 text-[10px] font-extrabold tracking-wider text-ink uppercase">
        {PRIORITIES.map((p) => (
          <span
            key={p}
            className={cn('rounded-sm px-1', target?.priority === p && 'bg-brand text-on-brand')}
          >
            {p === 'CRITICAL' ? 'Crit' : p.toLowerCase()}{' '}
            {stacks?.find((s) => s.priority === p)?.total ?? ''}
          </span>
        ))}
      </div>
    </div>
  );
}
