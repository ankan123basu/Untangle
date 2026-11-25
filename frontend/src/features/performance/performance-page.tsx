import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { ArrowDown, ArrowUp, Info } from 'lucide-react';
import { BotBadge } from '@/components/ui/bot-badge';
import { EmptyState, ErrorState, Skeleton } from '@/components/ui/feedback';
import { api } from '@/lib/api/client';
import type { AgentPerformance, PerformanceMetrics, PerformanceReport } from '@/lib/api/types';
import { queryKeys } from '@/lib/query-client';
import { best, HIGHER_IS_BETTER, sortAgents, type SortKey } from './performance-model';
import { cn, formatDuration, humanize } from '@/lib/utils';

const PERIODS = [7, 30, 90] as const;
type Period = (typeof PERIODS)[number];

const duration = (s: number | null) => (s === null ? '—' : formatDuration(s));
const percent = (p: number | null) => (p === null ? '—' : `${String(p)}%`);

function Tile({ label, value: shown, hint }: { label: string; value: string; hint: string }) {
  return (
    <div className="solid-panel flex flex-col gap-1 p-4">
      <p className="text-xs font-extrabold tracking-wide text-muted uppercase">{label}</p>
      <p className="font-display text-2xl font-bold xl:text-3xl">{shown}</p>
      <p className="text-xs text-muted">{hint}</p>
    </div>
  );
}

function TeamTiles({ team }: { team: PerformanceMetrics }) {
  return (
    <section
      aria-label="Team totals"
      className="grid grid-cols-2 gap-3 md:grid-cols-3 xl:grid-cols-5"
    >
      <Tile label="Resolved" value={String(team.resolved)} hint="tickets in this period" />
      <Tile
        label="Median handle time"
        value={duration(team.medianHandleSeconds)}
        hint="from getting a ticket to resolving it"
      />
      <Tile
        label="Median customer wait"
        value={duration(team.medianResolutionSeconds)}
        hint="from the customer asking to resolved"
      />
      <Tile
        label="SLA met"
        value={percent(team.slaMetPercent)}
        hint="resolved before the deadline"
      />
      <Tile
        label="Reopen rate"
        value={percent(team.reopenPercent)}
        hint="resolutions that did not stick"
      />
    </section>
  );
}

const HIGHLIGHTS: {
  key: Exclude<SortKey, 'openNow'>;
  title: string;
  colour: string;
  show: (m: PerformanceMetrics) => string;
}[] = [
  {
    key: 'resolved',
    title: 'Most resolved',
    colour: 'bg-stk-lemon',
    show: (m) => `${String(m.resolved)} tickets`,
  },
  {
    key: 'medianHandleSeconds',
    title: 'Fastest',
    colour: 'bg-stk-mint',
    show: (m) => `${duration(m.medianHandleSeconds)} median`,
  },
  {
    key: 'slaMetPercent',
    title: 'Best on SLA',
    colour: 'bg-stk-sky',
    show: (m) => `${percent(m.slaMetPercent)} met`,
  },
  {
    key: 'reopenPercent',
    title: 'Fewest reopens',
    colour: 'bg-stk-lilac',
    show: (m) => `${percent(m.reopenPercent)} reopened`,
  },
];

function Highlights({ agents, minimum }: { agents: AgentPerformance[]; minimum: number }) {
  const picks = HIGHLIGHTS.map((h) => ({ ...h, agent: best(agents, h.key) }));
  if (picks.every((p) => p.agent === null)) {
    return (
      <p className="solid-panel p-4 text-sm text-muted">
        Top performers appear once an agent has resolved at least {minimum} tickets in this period.
      </p>
    );
  }
  return (
    <section
      aria-label="Top performers"
      className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4"
    >
      {picks.map((p) => (
        <div
          key={p.key}
          className={cn(
            'sticker rounded-[16px] border-[3px] border-[#111] p-4 text-[#111] shadow-[5px_5px_0_0_#111]',
            p.colour,
          )}
        >
          <h2 className="text-xs font-extrabold tracking-wide uppercase">{p.title}</h2>
          {p.agent ? (
            <>
              <p className="font-display text-xl font-bold">{p.agent.fullName}</p>
              <p className="text-sm font-semibold">{p.show(p.agent.metrics)}</p>
            </>
          ) : (
            <p className="text-sm font-semibold">Not enough data yet</p>
          )}
        </div>
      ))}
    </section>
  );
}

const COLUMNS: { key: SortKey; label: string }[] = [
  { key: 'resolved', label: 'Resolved' },
  { key: 'avgHandleSeconds', label: 'Avg handle' },
  { key: 'medianHandleSeconds', label: 'Median handle' },
  { key: 'medianResolutionSeconds', label: 'Median wait' },
  { key: 'slaMetPercent', label: 'SLA met' },
  { key: 'reopenPercent', label: 'Reopened' },
  { key: 'openNow', label: 'Open now' },
];

function cell(a: AgentPerformance, key: SortKey): string {
  switch (key) {
    case 'resolved':
      return String(a.metrics.resolved);
    case 'openNow':
      return String(a.openNow);
    case 'slaMetPercent':
    case 'reopenPercent':
      return percent(a.metrics[key]);
    default:
      return duration(a.metrics[key]);
  }
}

function AgentTable({ agents, minimum }: { agents: AgentPerformance[]; minimum: number }) {
  const [sort, setSort] = useState<{ key: SortKey; ascending: boolean }>({
    key: 'resolved',
    ascending: false,
  });
  const rows = sortAgents(agents, sort.key, sort.ascending);

  return (
    <section className="solid-panel overflow-x-auto">
      <table className="stack-table w-full text-left text-sm md:min-w-[820px]">
        <caption className="sr-only">Performance per agent</caption>
        <thead className="bg-paper-2 text-xs font-bold text-muted uppercase">
          <tr>
            <th scope="col" className="px-4 py-2">
              Agent
            </th>
            {COLUMNS.map((c) => {
              const active = sort.key === c.key;
              return (
                <th
                  key={c.key}
                  scope="col"
                  className="py-2 pr-3"
                  aria-sort={active ? (sort.ascending ? 'ascending' : 'descending') : 'none'}
                >
                  <button
                    type="button"
                    className="inline-flex items-center gap-1 uppercase hover:text-ink"
                    onClick={() => {
                      setSort(
                        active
                          ? { key: c.key, ascending: !sort.ascending }
                          : { key: c.key, ascending: !HIGHER_IS_BETTER[c.key] },
                      );
                    }}
                  >
                    {c.label}
                    {active &&
                      (sort.ascending ? (
                        <ArrowUp aria-hidden="true" className="size-3" />
                      ) : (
                        <ArrowDown aria-hidden="true" className="size-3" />
                      ))}
                  </button>
                </th>
              );
            })}
          </tr>
        </thead>
        <tbody>
          {rows.map((a) => (
            <tr
              key={a.agentId}
              className={cn('border-t-2 border-line-soft', !a.ranked && 'text-muted')}
            >
              <th scope="row" className="px-4 py-3 font-semibold">
                <span className="text-ink">{a.fullName}</span> {a.ai && <BotBadge />}
                <span className="block text-xs font-normal text-muted">
                  {humanize(a.role)}
                  {!a.active && ' · deactivated'}
                  {!a.ranked && a.metrics.resolved > 0 && ` · under ${String(minimum)} resolved`}
                </span>
              </th>
              {COLUMNS.map((c) => (
                <td key={c.key} data-label={c.label} className="py-3 pr-3 font-mono">
                  {c.key === 'slaMetPercent' && a.metrics.slaMetPercent !== null ? (
                    <span className="flex min-w-[6rem] items-center gap-2">
                      <span
                        role="meter"
                        aria-label={`SLA met by ${a.fullName}`}
                        aria-valuemin={0}
                        aria-valuemax={100}
                        aria-valuenow={a.metrics.slaMetPercent}
                        className="h-2.5 flex-1 overflow-hidden rounded-sm border-2 border-line bg-paper-2"
                      >
                        <span
                          className="block h-full bg-pop"
                          style={{ width: `${String(a.metrics.slaMetPercent)}%` }}
                        />
                      </span>
                      {cell(a, c.key)}
                    </span>
                  ) : (
                    cell(a, c.key)
                  )}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}

/**
 * Team performance (ADR-030), for leads and admins: how fast and how well each agent resolves
 * tickets. Every number is computed on the server from one fact row per resolution.
 */
export default function PerformancePage() {
  const [days, setDays] = useState<Period>(30);
  const report = useQuery({
    queryKey: queryKeys.dashboard.performance(days),
    queryFn: async () =>
      (await api.get<PerformanceReport>('/dashboard/agent-performance', { params: { days } })).data,
    placeholderData: (previous) => previous,
  });
  const data = report.data;

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="font-display text-4xl font-bold tracking-tight">
            <span className="highlight">Performance</span>
          </h1>
          <p className="text-muted">How fast and how well each agent resolves tickets.</p>
        </div>
        <div role="group" aria-label="Period" className="flex gap-1">
          {PERIODS.map((p) => (
            <button
              key={p}
              type="button"
              aria-pressed={days === p}
              onClick={() => {
                setDays(p);
              }}
              className={cn(
                'press rounded-md border-2 px-3 py-1 text-sm font-bold',
                days === p
                  ? 'border-line bg-ink text-paper'
                  : 'border-line-soft bg-surface text-muted hover:border-line',
              )}
            >
              {p} days
            </button>
          ))}
        </div>
      </div>

      {report.isError ? (
        <section className="solid-panel">
          <ErrorState error={report.error} onRetry={() => void report.refetch()} />
        </section>
      ) : !data ? (
        <section className="flex flex-col gap-3">
          <Skeleton className="h-24" />
          <Skeleton className="h-40" />
        </section>
      ) : (
        <>
          <TeamTiles team={data.team} />
          <Highlights agents={data.agents} minimum={data.minResolvedToRank} />
          {data.agents.length === 0 ? (
            <section className="solid-panel">
              <EmptyState title="No agents yet" description="Add agents to see their numbers." />
            </section>
          ) : (
            <AgentTable agents={data.agents} minimum={data.minResolvedToRank} />
          )}
          <details className="solid-panel p-4 text-sm">
            <summary className="cursor-pointer font-bold">
              <Info aria-hidden="true" className="mr-1 inline size-4" />
              How these numbers work
            </summary>
            <ul className="mt-3 list-disc space-y-1 pl-5">
              <li>
                Each resolution counts for the agent who owned the ticket at that moment, even when
                a lead pressed the button (for example approving Unty's proposal).
              </li>
              <li>
                <b>Handle time</b> runs from when the agent got the ticket to when they resolved it.
                <b> Customer wait</b> runs from when the customer asked.
              </li>
              <li>
                <b>Median</b> is the middle value, so one very slow ticket doesn't hide a fast
                agent. The average is shown too.
              </li>
              <li>
                <b>Reopened</b>: the ticket came back after being resolved (reopened, or sent back
                to work).
              </li>
              <li>
                Top performers need at least {data.minResolvedToRank} resolutions in the period.
                Times don't adjust for difficulty: a CRITICAL outage and a LOW question count the
                same.
              </li>
            </ul>
          </details>
        </>
      )}
    </div>
  );
}
