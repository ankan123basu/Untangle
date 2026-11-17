import { lazy, Suspense, type ReactNode } from 'react';
import { Link } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { AlertOctagon, Flame, Inbox, UserRound } from 'lucide-react';
import CountUp from '@/components/reactbits/CountUp';
import SpotlightCard from '@/components/reactbits/SpotlightCard';
import { Card, CardBody, CardHeader, CardTitle } from '@/components/ui/card';
import { TiltCard } from '@/components/ui/tilt-card';
import { WhenVisible } from '@/components/ui/when-visible';
import { ErrorState, Skeleton } from '@/components/ui/feedback';
import { api } from '@/lib/api/client';
import { PRIORITIES, type DashboardSummary, type Priority } from '@/lib/api/types';
import { queryKeys } from '@/lib/query-client';
import { cn, humanize, timeAgo } from '@/lib/utils';
import { useCan } from '@/features/auth/session-store';
import { TicketBrowser } from '@/features/tickets/ticket-browser';
import { useWorkload } from '@/features/tickets/ticket-api';

// Recharts is the heaviest dependency on this screen; load it after the numbers are on screen.
const TrendChart = lazy(() => import('./trend-chart'));

const PRIORITY_BAR: Record<Priority, string> = {
  CRITICAL: 'bg-prio-critical-bg text-prio-critical-fg',
  HIGH: 'bg-prio-high-bg text-prio-high-fg',
  MEDIUM: 'bg-prio-medium-bg text-prio-medium-fg',
  LOW: 'bg-prio-low-bg text-prio-low-fg',
};

function useSummary() {
  return useQuery({
    queryKey: queryKeys.dashboard.summary,
    queryFn: async () => (await api.get<DashboardSummary>('/dashboard/summary')).data,
    refetchInterval: 60_000,
  });
}

/** A number that hasn't arrived yet: same box as the real value, so nothing shifts (CLS). */
function Placeholder({ className }: { className: string }) {
  return (
    <span aria-hidden="true" className={cn('animate-pulse rounded-sm bg-paper-2', className)} />
  );
}

/** The hero: the one number that matters, and what it's made of. */
function UnresolvedHero({ summary }: { summary: DashboardSummary | undefined }) {
  return (
    <section
      aria-labelledby="unresolved-title"
      className="solid-panel flex flex-col gap-5 p-6 md:flex-row md:items-end md:justify-between"
    >
      <div>
        <h2 id="unresolved-title" className="text-sm font-bold tracking-wider text-muted uppercase">
          Unresolved tickets
        </h2>
        <p className="font-display text-7xl leading-none font-bold tabular-nums md:text-8xl">
          {summary ? (
            <>
              <span className="sr-only">{summary.unresolved}</span>
              <span aria-hidden="true">
                <CountUp to={summary.unresolved} duration={1.2} separator="," trigger="load" />
              </span>
            </>
          ) : (
            <Placeholder className="inline-block h-[0.85em] w-[1.6em] align-baseline" />
          )}
        </p>
        <p className="mt-2 text-sm text-muted">
          {summary ? `Updated ${timeAgo(summary.generatedAt)}` : 'Loading'} · refreshes every minute
        </p>
      </div>
      <div className="w-full md:max-w-md">
        <p className="mb-2 text-sm font-semibold">By priority</p>
        <div
          className="flex h-4 overflow-hidden rounded-sm border-2 border-line"
          aria-hidden="true"
        >
          {PRIORITIES.map((p) => {
            const n = summary?.unresolvedByPriority[p] ?? 0;
            return n > 0 ? (
              <div
                key={p}
                className={cn(PRIORITY_BAR[p], 'border-r-2 border-line last:border-r-0')}
                style={{ flexGrow: n }}
              />
            ) : null;
          })}
        </div>
        <ul className="mt-3 grid grid-cols-2 gap-2 sm:grid-cols-4">
          {PRIORITIES.map((p) => (
            <li key={p}>
              <Link
                to={`/tickets?priority=${p}&status=OPEN&status=IN_PROGRESS`}
                className="flex flex-col rounded-md border-2 border-line-soft px-2 py-1 hover:border-line"
              >
                <span className="text-xs font-bold text-muted uppercase">{humanize(p)}</span>
                <span className="font-display text-xl font-bold tabular-nums">
                  {summary ? (
                    (summary.unresolvedByPriority[p] ?? 0)
                  ) : (
                    <Placeholder className="inline-block h-5 w-6" />
                  )}
                </span>
              </Link>
            </li>
          ))}
        </ul>
      </div>
    </section>
  );
}

function Kpi({
  label,
  value,
  to,
  icon,
  tone,
}: {
  label: string;
  value: number | undefined;
  to: string;
  icon: ReactNode;
  tone?: 'danger';
}) {
  return (
    <TiltCard>
      <Link to={to} className="block rounded-[12px] focus-visible:outline-offset-4">
        <SpotlightCard
          className="!rounded-[12px] border-2 border-line !p-4"
          spotlightColor="var(--ut-brand)"
          style={
            {
              '--spotlight-card-surface': 'var(--ut-surface)',
              '--spotlight-card-border': 'transparent',
              '--spotlight-card-shadow': 'var(--ut-shadow-hard)',
            } as React.CSSProperties
          }
        >
          <div className="flex items-start justify-between gap-2">
            <p className="text-sm font-semibold text-muted">{label}</p>
            <span
              className={cn(
                'rounded-sm border-2 border-line p-1',
                tone === 'danger' && (value ?? 0) > 0 ? 'bg-danger text-on-danger' : 'bg-paper-2',
              )}
            >
              {icon}
            </span>
          </div>
          <p
            className={cn(
              'font-display text-4xl font-bold tabular-nums',
              tone === 'danger' && (value ?? 0) > 0 && 'text-danger',
            )}
          >
            {value}
          </p>
        </SpotlightCard>
      </Link>
    </TiltCard>
  );
}

function WorkloadCard() {
  const workload = useWorkload();
  const max = Math.max(1, ...(workload.data ?? []).map((w) => w.open + w.inProgress));
  return (
    <Card>
      <CardHeader>
        <CardTitle>Agent workload</CardTitle>
      </CardHeader>
      <CardBody>
        {workload.isError ? (
          <ErrorState error={workload.error} />
        ) : !workload.data ? (
          <Skeleton className="h-64" />
        ) : (
          <table className="w-full text-left text-sm">
            <caption className="sr-only">Open, in-progress and breached tickets per agent</caption>
            <thead className="text-xs text-muted uppercase">
              <tr>
                <th scope="col" className="pb-2">
                  Agent
                </th>
                <th scope="col" className="pb-2">
                  Load
                </th>
                <th scope="col" className="pb-2 text-right">
                  Breached
                </th>
              </tr>
            </thead>
            <tbody>
              {workload.data.map((w) => {
                const load = w.open + w.inProgress;
                return (
                  <tr key={w.agentId} className="border-t-2 border-line-soft">
                    <th scope="row" className="py-2 pr-2 font-semibold">
                      <Link
                        to={`/tickets?assigneeId=${w.agentId}&status=OPEN&status=IN_PROGRESS`}
                        className="hover:underline"
                      >
                        {w.fullName}
                      </Link>
                      <span className="block text-xs font-normal text-muted">
                        {humanize(w.role)}
                      </span>
                    </th>
                    <td className="w-1/2 py-2 pr-2">
                      <div className="flex items-center gap-2">
                        <div
                          className="h-3 flex-1 rounded-sm border-2 border-line bg-paper-2"
                          aria-hidden="true"
                        >
                          <div
                            className="h-full bg-accent"
                            style={{ width: `${String((load / max) * 100)}%` }}
                          />
                        </div>
                        <span className="w-24 text-xs tabular-nums">
                          {w.open} open · {w.inProgress} active
                        </span>
                      </div>
                    </td>
                    <td
                      className={cn(
                        'py-2 text-right font-bold tabular-nums',
                        w.breached > 0 && 'text-danger',
                      )}
                    >
                      {w.breached}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </CardBody>
    </Card>
  );
}

/** Screen 2 (§12.5): what needs attention right now. */
export default function DashboardPage() {
  const summary = useSummary();
  const canSeeWorkload = useCan('AGENT_WORKLOAD_VIEW');

  return (
    <div className="flex flex-col gap-6">
      <h1 className="sr-only">Dashboard</h1>
      {summary.isError ? (
        <section className="solid-panel">
          <ErrorState error={summary.error} onRetry={() => void summary.refetch()} />
        </section>
      ) : (
        <>
          {/* Frames render at once; numbers fill in. Keeps layout stable and paints early. */}
          <div aria-busy={summary.data ? undefined : true}>
            <UnresolvedHero summary={summary.data} />
          </div>
          <section aria-label="Key numbers" className="grid grid-cols-2 gap-4 lg:grid-cols-4">
            <Kpi
              label="SLA breached"
              value={summary.data?.slaBreached}
              to="/tickets?slaBreached=true&sort=slaDueAt%2Casc"
              icon={<AlertOctagon className="size-4" />}
              tone="danger"
            />
            <Kpi
              label="Escalated"
              value={summary.data?.escalated}
              to="/tickets?escalated=true"
              icon={<Flame className="size-4" />}
              tone="danger"
            />
            <Kpi
              label="Unassigned"
              value={summary.data?.unassigned}
              to="/tickets?unassigned=true"
              icon={<Inbox className="size-4" />}
            />
            <Kpi
              label="My open tickets"
              value={summary.data?.myOpen}
              to="/tickets?mine=true&status=OPEN&status=IN_PROGRESS"
              icon={<UserRound className="size-4" />}
            />
          </section>
        </>
      )}

      <div
        className={cn('grid gap-6', canSeeWorkload && 'xl:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]')}
      >
        <Card>
          <CardHeader>
            <CardTitle>Created vs resolved · last 14 days</CardTitle>
          </CardHeader>
          <CardBody>
            {/* Recharts is ~120 kB; fetch it only once the chart is about to be seen. */}
            <WhenVisible
              fallback={<Skeleton className="h-64" />}
              ready={!summary.isPending}
              delay={800}
            >
              <Suspense fallback={<Skeleton className="h-64" />}>
                <TrendChart />
              </Suspense>
            </WhenVisible>
          </CardBody>
        </Card>
        {canSeeWorkload && <WorkloadCard />}
      </div>

      {/* Below the fold everywhere: load it after the key numbers so it doesn't slow first paint. */}
      <WhenVisible fallback={<Skeleton className="h-96" />} ready={!summary.isPending} delay={800}>
        <TicketBrowser title="Queue" compact />
      </WhenVisible>
    </div>
  );
}
