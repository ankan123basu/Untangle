import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Play, Shuffle } from 'lucide-react';
import { toast } from 'sonner';
import { BotBadge } from '@/components/ui/bot-badge';
import { Button } from '@/components/ui/button';
import { EmptyState, ErrorState, Skeleton } from '@/components/ui/feedback';
import { Input } from '@/components/ui/field';
import { api } from '@/lib/api/client';
import { ApiError, messageFor } from '@/lib/api/problem';
import {
  CATEGORIES,
  type AgentRoutingView,
  type AutoAssignOverview,
  type AutoAssignRun,
  type Category,
} from '@/lib/api/types';
import { queryKeys } from '@/lib/query-client';
import { cn, formatDateTime, humanize, timeAgo } from '@/lib/utils';

function showError(error: unknown) {
  toast.error(error instanceof ApiError ? messageFor(error) : 'Something went wrong.');
}

/** An accessible on/off switch in the brutalist style. */
function Switch({
  checked,
  label,
  disabled,
  onChange,
}: {
  checked: boolean;
  label: string;
  disabled?: boolean;
  onChange: (next: boolean) => void;
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      disabled={disabled}
      onClick={() => {
        onChange(!checked);
      }}
      className={cn(
        'press relative inline-flex h-8 w-14 shrink-0 items-center rounded-full border-[3px] border-line transition-colors disabled:opacity-50',
        checked ? 'bg-pop' : 'bg-paper-2',
      )}
    >
      <span
        aria-hidden="true"
        className={cn(
          'absolute top-1/2 size-5 -translate-y-1/2 rounded-full border-2 border-line bg-surface transition-[left] motion-reduce:transition-none',
          checked ? 'left-7' : 'left-1',
        )}
      />
    </button>
  );
}

/** The highest workload shown on the bar; anything above fills it. */
const BAR_MAX = 30;

function AgentRow({
  agent,
  saving,
  onSave,
}: {
  agent: AgentRoutingView;
  saving: boolean;
  onSave: (autoAssign: boolean, skills: Category[]) => void;
}) {
  const skills = new Set(agent.skills);
  const toggleSkill = (c: Category) => {
    const next = new Set(skills);
    if (next.has(c)) next.delete(c);
    else next.add(c);
    onSave(
      agent.autoAssign,
      CATEGORIES.filter((x) => next.has(x)),
    );
  };
  const fill = Math.min(100, (agent.load / BAR_MAX) * 100);
  return (
    <tr className={cn('border-t-2 border-line-soft', !agent.autoAssign && 'text-muted')}>
      <th scope="row" className="px-4 py-3 font-semibold">
        <span className="text-ink">{agent.fullName}</span> {agent.ai && <BotBadge />}
        <span className="block text-xs font-normal text-muted">
          {humanize(agent.role)}
          {agent.ai && ' · LOW and MEDIUM tickets only'}
        </span>
      </th>
      <td data-label="Takes auto work" className="py-3 pr-3">
        <Switch
          checked={agent.autoAssign}
          label={`${agent.fullName} takes automatic work`}
          disabled={saving}
          onChange={(next) => {
            onSave(next, agent.skills);
          }}
        />
      </td>
      <td data-label="Handles" className="py-3 pr-3">
        <div
          role="group"
          aria-label={`Categories ${agent.fullName} handles`}
          className="flex max-w-[22rem] flex-wrap gap-1"
        >
          {CATEGORIES.map((c) => (
            <button
              key={c}
              type="button"
              aria-pressed={skills.has(c)}
              disabled={saving}
              onClick={() => {
                toggleSkill(c);
              }}
              className={cn(
                'rounded-md border-2 px-2 py-0.5 text-xs font-bold disabled:opacity-50',
                skills.has(c)
                  ? 'border-line bg-ink text-paper'
                  : 'border-line-soft bg-surface text-muted hover:border-line',
              )}
            >
              {humanize(c)}
            </button>
          ))}
        </div>
        {skills.size === 0 && (
          <p className="mt-1 text-xs text-muted">No category picked: handles all of them.</p>
        )}
      </td>
      <td data-label="Workload" className="py-3 pr-3">
        <div className="flex min-w-[9rem] items-center gap-2">
          <div
            role="meter"
            aria-label={`Workload of ${agent.fullName}`}
            aria-valuemin={0}
            aria-valuemax={BAR_MAX}
            aria-valuenow={agent.load}
            className="h-3 flex-1 overflow-hidden rounded-sm border-2 border-line bg-paper-2"
          >
            <div className="h-full bg-pop" style={{ width: `${String(fill)}%` }} />
          </div>
          <span className="font-mono text-xs whitespace-nowrap text-ink">
            {agent.load} · {agent.openTickets} open
          </span>
        </div>
      </td>
      <td data-label="Last auto ticket" className="py-3 pr-4 text-sm">
        {agent.lastAutoAssignedAt ? (
          <time
            dateTime={agent.lastAutoAssignedAt}
            title={formatDateTime(agent.lastAutoAssignedAt)}
          >
            {timeAgo(agent.lastAutoAssignedAt)}
          </time>
        ) : (
          'Never'
        )}
      </td>
    </tr>
  );
}

function CapForm({
  value,
  disabled,
  onSave,
}: {
  value: number;
  disabled: boolean;
  onSave: (cap: number) => void;
}) {
  const [draft, setDraft] = useState(String(value));
  const cap = Number(draft);
  const valid = Number.isInteger(cap) && cap >= 1 && cap <= 100;
  return (
    <form
      className="flex flex-wrap items-end gap-2"
      noValidate
      onSubmit={(e) => {
        e.preventDefault();
        if (valid) onSave(cap);
      }}
    >
      <label className="flex flex-col gap-1 text-sm font-bold">
        Max open tickets per agent
        <Input
          type="number"
          inputMode="numeric"
          min={1}
          max={100}
          className="w-28"
          value={draft}
          aria-invalid={!valid}
          onChange={(e) => {
            setDraft(e.target.value);
          }}
        />
      </label>
      <Button type="submit" size="sm" disabled={disabled || !valid || cap === value}>
        Save cap
      </Button>
      {!valid && <p className="w-full text-xs text-danger">Enter a number from 1 to 100.</p>}
    </form>
  );
}

/**
 * Auto-assign (ADR-029): leads switch it on, set the per-agent cap and choose who takes which
 * work. The server does the assigning; this screen only shows and changes the settings.
 */
export default function AutoAssignPage() {
  const client = useQueryClient();
  const overview = useQuery({
    queryKey: queryKeys.autoAssign,
    queryFn: async () => (await api.get<AutoAssignOverview>('/auto-assign')).data,
    refetchInterval: 15_000,
  });

  const saveSettings = useMutation({
    mutationFn: async (v: { enabled: boolean; maxOpenPerAgent: number }) =>
      (await api.put<AutoAssignOverview>('/auto-assign/settings', v)).data,
    onSuccess: (data, v) => {
      client.setQueryData(queryKeys.autoAssign, data);
      toast.success(v.enabled ? 'Auto-assign is on' : 'Auto-assign is off');
    },
    onError: showError,
  });

  const saveAgent = useMutation({
    mutationFn: async (v: { agentId: string; autoAssign: boolean; skills: Category[] }) =>
      (
        await api.put<AutoAssignOverview>(`/auto-assign/agents/${v.agentId}`, {
          autoAssign: v.autoAssign,
          skills: v.skills,
        })
      ).data,
    onSuccess: (data) => {
      client.setQueryData(queryKeys.autoAssign, data);
    },
    onError: showError,
  });

  const run = useMutation({
    mutationFn: async () => (await api.post<AutoAssignRun>('/auto-assign/run')).data,
    onSuccess: (result) => {
      toast.success(
        `Assigned ${String(result.assigned)} ticket${result.assigned === 1 ? '' : 's'} · ${String(result.waiting)} still waiting`,
      );
      void client.invalidateQueries({ queryKey: queryKeys.autoAssign });
      void client.invalidateQueries({ queryKey: ['tickets'] });
      void client.invalidateQueries({ queryKey: queryKeys.dashboard.all });
    },
    onError: showError,
  });

  const data = overview.data;
  const takers = data?.agents.filter((a) => a.autoAssign).length ?? 0;

  return (
    <div className="flex flex-col gap-4">
      <div>
        <h1 className="font-display text-4xl font-bold tracking-tight">
          <span className="highlight">Auto-assign</span>
        </h1>
        <p className="text-muted">
          Each waiting ticket goes to the least busy agent who handles it, most urgent first.
        </p>
      </div>

      {overview.isError ? (
        <section className="solid-panel">
          <ErrorState error={overview.error} onRetry={() => void overview.refetch()} />
        </section>
      ) : !data ? (
        <section className="solid-panel flex flex-col gap-3 p-4">
          <Skeleton className="h-24" />
          <Skeleton className="h-12" />
          <Skeleton className="h-12" />
        </section>
      ) : (
        <>
          <div className="grid grid-cols-1 gap-4 lg:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
            <section aria-labelledby="aa-switch" className="solid-panel flex flex-col gap-4 p-5">
              <div className="flex flex-wrap items-center gap-3">
                <Switch
                  checked={data.enabled}
                  label="Automatic assignment"
                  disabled={saveSettings.isPending}
                  onChange={(enabled) => {
                    saveSettings.mutate({ enabled, maxOpenPerAgent: data.maxOpenPerAgent });
                  }}
                />
                <div>
                  <h2 id="aa-switch" className="font-display text-xl font-bold">
                    Automatic assignment is {data.enabled ? 'on' : 'off'}
                  </h2>
                  <p className="text-sm text-muted">
                    {data.enabled
                      ? 'New tickets are assigned as they arrive; the queue is swept every 30 seconds.'
                      : 'Tickets wait in the queue until someone assigns them.'}
                  </p>
                </div>
              </div>
              <CapForm
                key={data.maxOpenPerAgent}
                value={data.maxOpenPerAgent}
                disabled={saveSettings.isPending}
                onSave={(maxOpenPerAgent) => {
                  saveSettings.mutate({ enabled: data.enabled, maxOpenPerAgent });
                }}
              />
            </section>

            <section
              aria-labelledby="aa-queue"
              className="sticker flex flex-col justify-between gap-3 rounded-[16px] border-[3px] border-[#111] bg-stk-lemon p-5 text-[#111] shadow-[5px_5px_0_0_#111]"
            >
              <div>
                <h2 id="aa-queue" className="text-xs font-extrabold tracking-wide uppercase">
                  Waiting for an agent
                </h2>
                <p className="font-display text-5xl font-bold">{data.waiting}</p>
                <p className="text-sm font-semibold">
                  {takers} {takers === 1 ? 'agent takes' : 'agents take'} automatic work
                </p>
              </div>
              <Button
                variant="primary"
                icon={<Play className="size-4" />}
                loading={run.isPending}
                disabled={data.waiting === 0}
                onClick={() => {
                  run.mutate();
                }}
              >
                Assign waiting now
              </Button>
            </section>
          </div>

          <details className="solid-panel p-4 text-sm">
            <summary className="cursor-pointer font-bold">
              <Shuffle aria-hidden="true" className="mr-1 inline size-4" />
              How a ticket is matched
            </summary>
            <ol className="mt-3 list-decimal space-y-1 pl-5">
              <li>The queue is taken most urgent first (earliest SLA deadline).</li>
              <li>
                Workload = CRITICAL 4, HIGH 3, MEDIUM 2, LOW 1 for each unresolved ticket. The agent
                with the lowest workload gets the ticket; on a tie, whoever waited longest.
              </li>
              <li>
                They must handle the category, be under the cap, and not be the person who just gave
                that ticket back.
              </li>
              <li>
                Unty (the AI) only takes LOW and MEDIUM tickets, and only if switched on here.
              </li>
              <li>If nobody fits, the ticket waits and SLA escalation still watches it.</li>
            </ol>
          </details>

          <section className="solid-panel overflow-x-auto">
            {data.agents.length === 0 ? (
              <EmptyState title="No active agents" />
            ) : (
              <table className="stack-table w-full text-left text-sm md:min-w-[760px]">
                <caption className="sr-only">Who takes automatic work</caption>
                <thead className="bg-paper-2 text-xs font-bold text-muted uppercase">
                  <tr>
                    <th scope="col" className="px-4 py-2">
                      Agent
                    </th>
                    <th scope="col" className="py-2 pr-3">
                      Takes auto work
                    </th>
                    <th scope="col" className="py-2 pr-3">
                      Handles
                    </th>
                    <th scope="col" className="py-2 pr-3">
                      Workload
                    </th>
                    <th scope="col" className="py-2 pr-4">
                      Last auto ticket
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {data.agents.map((a) => (
                    <AgentRow
                      key={a.agentId}
                      agent={a}
                      saving={saveAgent.isPending && saveAgent.variables.agentId === a.agentId}
                      onSave={(autoAssign, skills) => {
                        saveAgent.mutate({ agentId: a.agentId, autoAssign, skills });
                      }}
                    />
                  ))}
                </tbody>
              </table>
            )}
          </section>
        </>
      )}
    </div>
  );
}
