import { useEffect, useState, type ReactNode } from 'react';
import { Link, useSearchParams } from 'react-router';
import { ChevronLeft, ChevronRight, Search, X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { EmptyState, ErrorState, Skeleton } from '@/components/ui/feedback';
import { Input, Select } from '@/components/ui/field';
import { CATEGORIES, PRIORITIES, STATUSES, type TicketSummary } from '@/lib/api/types';
import { cn, humanize, timeAgo } from '@/lib/utils';
import { EscalationBadge, PriorityBadge, SlaIndicator, SourceBadge, StatusBadge } from './badges';
import {
  activeQuickView,
  hasFilters,
  PAGE_SIZES,
  parseQuery,
  QUICK_VIEWS,
  SORTS,
  toSearchParams,
  type TicketQuery,
} from './filters';
import { useAgentOptions, useTicketList } from './ticket-api';

type QueryPatch = { [K in keyof TicketQuery]?: TicketQuery[K] | undefined };

/** A toggle chip used for multi-select filters (status, priority) and flags. */
function Chip({
  pressed,
  onToggle,
  children,
}: {
  pressed: boolean;
  onToggle: () => void;
  children: ReactNode;
}) {
  return (
    <button
      type="button"
      aria-pressed={pressed}
      onClick={onToggle}
      className={cn(
        'h-8 rounded-md border-2 px-2.5 text-xs font-bold whitespace-nowrap transition-colors',
        pressed
          ? 'border-line bg-ink text-paper shadow-hard-sm'
          : 'border-line-soft bg-surface text-ink hover:border-line',
      )}
    >
      {children}
    </button>
  );
}

function toggle<T>(list: T[] | undefined, value: T): T[] | undefined {
  const current = list ?? [];
  const next = current.includes(value) ? current.filter((v) => v !== value) : [...current, value];
  return next.length ? next : undefined;
}

/** Debounces the search box so typing doesn't fire a request per keystroke. */
function SearchBox({ value, onChange }: { value: string; onChange: (q: string) => void }) {
  const [draft, setDraft] = useState(value);
  const [synced, setSynced] = useState(value);
  if (synced !== value) {
    // URL changed from elsewhere (back button, quick view): adopt it.
    setSynced(value);
    setDraft(value);
  }
  useEffect(() => {
    if (draft === value) return;
    const timer = window.setTimeout(() => {
      onChange(draft);
    }, 300);
    return () => {
      window.clearTimeout(timer);
    };
  }, [draft, value, onChange]);
  return (
    <div className="relative min-w-0 flex-1">
      <Search
        aria-hidden="true"
        className="absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted"
      />
      <Input
        type="search"
        aria-label="Search tickets"
        placeholder="Search number, subject, customer…"
        className="pl-9"
        value={draft}
        onChange={(e) => {
          setDraft(e.target.value);
        }}
      />
    </div>
  );
}

function TicketRow({ ticket }: { ticket: TicketSummary }) {
  return (
    <tr className="group border-t-2 border-line-soft transition-colors hover:bg-paper-2/60">
      <td className="py-3 pr-3 pl-4 align-top">
        <Link
          to={`/tickets/${ticket.id}`}
          className="font-mono text-sm font-bold text-accent underline-offset-4 group-hover:underline after:absolute after:inset-0"
        >
          {ticket.ticketNumber}
        </Link>
      </td>
      <td className="max-w-0 py-3 pr-3 align-top">
        <p className="truncate font-semibold">{ticket.subject}</p>
        <p className="truncate text-sm text-muted">
          {ticket.customerName} · {humanize(ticket.category)}
        </p>
        <div className="mt-1 flex flex-wrap gap-1 empty:hidden">
          <EscalationBadge level={ticket.escalationLevel} />
          <SourceBadge source={ticket.source} />
        </div>
      </td>
      <td className="py-3 pr-3 align-top">
        <PriorityBadge priority={ticket.priority} />
      </td>
      <td className="py-3 pr-3 align-top">
        <StatusBadge status={ticket.status} />
      </td>
      <td className="hidden py-3 pr-3 align-top text-sm md:table-cell">
        {ticket.assignee?.fullName ?? <span className="text-muted italic">Unassigned</span>}
      </td>
      <td className="hidden py-3 pr-3 align-top lg:table-cell">
        <SlaIndicator
          status={ticket.status}
          slaDueAt={ticket.slaDueAt}
          slaBreached={ticket.slaBreached}
          tick={30_000}
        />
      </td>
      <td className="hidden py-3 pr-4 align-top text-sm whitespace-nowrap text-muted xl:table-cell">
        <time dateTime={ticket.createdAt}>{timeAgo(ticket.createdAt)}</time>
      </td>
    </tr>
  );
}

/**
 * The ticket table with every filter from §12.5, all synced to URL query params. Used on the
 * dashboard and on /tickets.
 */
export function TicketBrowser({
  title = 'Tickets',
  compact = false,
}: {
  title?: string;
  compact?: boolean;
}) {
  const [params, setParams] = useSearchParams();
  const defaultSize = compact ? 10 : 20;
  const query = parseQuery(params, defaultSize);
  const list = useTicketList(query);
  const agents = useAgentOptions();
  const quickView = activeQuickView(params);

  const update = (patch: QueryPatch, resetPage = true) => {
    const merged: Record<string, unknown> = {
      ...query,
      ...patch,
      ...(resetPage ? { page: 0 } : {}),
    };
    const next = Object.fromEntries(Object.entries(merged).filter(([, v]) => v !== undefined));
    setParams(toSearchParams(next as unknown as TicketQuery, defaultSize));
  };

  const data = list.data;
  const from = data ? data.page * data.size + 1 : 0;
  const to = data ? Math.min(data.totalElements, (data.page + 1) * data.size) : 0;

  return (
    <section aria-labelledby="ticket-browser-title" className="solid-panel overflow-hidden">
      <div className="flex flex-col gap-3 border-b-2 border-line p-4">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h2 id="ticket-browser-title" className="font-display text-xl font-bold">
            {title}
          </h2>
          <nav aria-label="Quick views" className="flex flex-wrap gap-1.5">
            {QUICK_VIEWS.map((view) => (
              <Link
                key={view.id}
                to={{ search: view.search }}
                aria-current={quickView === view.id ? 'page' : undefined}
                className={cn(
                  'rounded-md border-2 px-2.5 py-1 text-xs font-bold',
                  quickView === view.id
                    ? 'border-line bg-brand text-on-brand shadow-hard-sm'
                    : 'border-transparent text-muted hover:border-line-soft hover:text-ink',
                )}
              >
                {view.label}
              </Link>
            ))}
          </nav>
        </div>

        <div className="flex flex-col gap-2 md:flex-row">
          <SearchBox
            value={query.q ?? ''}
            onChange={(q) => {
              update({ q: q.trim() || undefined });
            }}
          />
          <div className="grid grid-cols-2 gap-2 md:flex">
            <Select
              aria-label="Category"
              value={query.category?.[0] ?? ''}
              onChange={(e) => {
                update({
                  category: e.target.value
                    ? [e.target.value as (typeof CATEGORIES)[number]]
                    : undefined,
                });
              }}
            >
              <option value="">All categories</option>
              {CATEGORIES.map((c) => (
                <option key={c} value={c}>
                  {humanize(c)}
                </option>
              ))}
            </Select>
            <Select
              aria-label="Assignee"
              value={query.unassigned ? '__none' : (query.assigneeId ?? '')}
              onChange={(e) => {
                const v = e.target.value;
                update({
                  assigneeId: v && v !== '__none' ? v : undefined,
                  unassigned: v === '__none' ? true : undefined,
                  mine: undefined,
                });
              }}
            >
              <option value="">Any agent</option>
              <option value="__none">Unassigned</option>
              {agents.data?.map((a) => (
                <option key={a.id} value={a.id}>
                  {a.fullName}
                </option>
              ))}
            </Select>
            <Select
              aria-label="Sort"
              className="col-span-2"
              value={query.sort}
              onChange={(e) => {
                update({ sort: e.target.value as TicketQuery['sort'] });
              }}
            >
              {SORTS.map((s) => (
                <option key={s.value} value={s.value}>
                  {s.label}
                </option>
              ))}
            </Select>
          </div>
        </div>

        <div className="flex flex-wrap items-center gap-1.5" role="group" aria-label="Filters">
          {STATUSES.map((s) => (
            <Chip
              key={s}
              pressed={query.status?.includes(s) ?? false}
              onToggle={() => {
                update({ status: toggle(query.status, s) });
              }}
            >
              {humanize(s)}
            </Chip>
          ))}
          <span aria-hidden="true" className="mx-1 h-5 w-0.5 bg-line-soft" />
          {PRIORITIES.map((p) => (
            <Chip
              key={p}
              pressed={query.priority?.includes(p) ?? false}
              onToggle={() => {
                update({ priority: toggle(query.priority, p) });
              }}
            >
              {humanize(p)}
            </Chip>
          ))}
          <span aria-hidden="true" className="mx-1 h-5 w-0.5 bg-line-soft" />
          <Chip
            pressed={query.mine ?? false}
            onToggle={() => {
              update({
                mine: query.mine ? undefined : true,
                assigneeId: undefined,
                unassigned: undefined,
              });
            }}
          >
            Mine
          </Chip>
          <Chip
            pressed={query.escalated ?? false}
            onToggle={() => {
              update({ escalated: query.escalated ? undefined : true });
            }}
          >
            Escalated
          </Chip>
          <Chip
            pressed={query.slaBreached ?? false}
            onToggle={() => {
              update({ slaBreached: query.slaBreached ? undefined : true });
            }}
          >
            SLA breached
          </Chip>
          {hasFilters(query) && (
            <Button
              variant="ghost"
              size="sm"
              icon={<X className="size-4" />}
              onClick={() => {
                setParams(new URLSearchParams());
              }}
            >
              Clear
            </Button>
          )}
        </div>
      </div>

      {list.isError ? (
        <ErrorState
          error={list.error}
          onRetry={() => {
            void list.refetch();
          }}
        />
      ) : !data ? (
        <div className="flex flex-col gap-3 p-4" aria-busy="true" aria-label="Loading tickets">
          {Array.from({ length: 5 }, (_, i) => (
            <Skeleton key={i} className="h-14" />
          ))}
        </div>
      ) : data.content.length === 0 ? (
        <EmptyState
          title={hasFilters(query) ? 'No tickets match these filters' : 'No tickets yet'}
          description={
            hasFilters(query)
              ? 'Try removing a filter or searching for something else.'
              : 'New complaints from partners and agents will appear here.'
          }
          action={
            hasFilters(query) ? (
              <Button
                onClick={() => {
                  setParams(new URLSearchParams());
                }}
              >
                Clear filters
              </Button>
            ) : (
              <Link to="/tickets/new" className="font-semibold text-accent underline">
                Create a ticket
              </Link>
            )
          }
        />
      ) : (
        <div
          className={cn(
            'overflow-x-auto transition-opacity',
            list.isPlaceholderData && 'opacity-60',
          )}
        >
          <table className="w-full table-fixed border-collapse text-left">
            <caption className="sr-only">
              {title}: {data.totalElements} tickets, page {data.page + 1} of{' '}
              {Math.max(1, data.totalPages)}
            </caption>
            <thead className="bg-paper-2 text-xs font-bold tracking-wide text-muted uppercase">
              <tr>
                <th scope="col" className="w-32 py-2 pr-3 pl-4">
                  Ticket
                </th>
                <th scope="col" className="py-2 pr-3">
                  Subject
                </th>
                <th scope="col" className="w-24 py-2 pr-3">
                  Priority
                </th>
                <th scope="col" className="w-28 py-2 pr-3">
                  Status
                </th>
                <th scope="col" className="hidden w-36 py-2 pr-3 md:table-cell">
                  Assignee
                </th>
                <th scope="col" className="hidden w-44 py-2 pr-3 lg:table-cell">
                  SLA
                </th>
                <th scope="col" className="hidden w-32 py-2 pr-4 xl:table-cell">
                  Created
                </th>
              </tr>
            </thead>
            <tbody className="[&>tr]:relative">
              {data.content.map((t) => (
                <TicketRow key={t.id} ticket={t} />
              ))}
            </tbody>
          </table>
        </div>
      )}

      {data && data.totalElements > 0 && (
        <div className="flex flex-wrap items-center justify-between gap-3 border-t-2 border-line px-4 py-3 text-sm">
          <p aria-live="polite">
            <span className="font-semibold">
              {from}–{to}
            </span>{' '}
            of {data.totalElements}
          </p>
          <div className="flex items-center gap-2">
            {!compact && (
              <Select
                aria-label="Rows per page"
                className="h-8 w-auto py-0"
                value={query.size}
                onChange={(e) => {
                  update({ size: Number(e.target.value) });
                }}
              >
                {PAGE_SIZES.map((n) => (
                  <option key={n} value={n}>
                    {n} / page
                  </option>
                ))}
              </Select>
            )}
            <Button
              size="sm"
              aria-label="Previous page"
              disabled={data.page === 0}
              icon={<ChevronLeft className="size-4" />}
              onClick={() => {
                update({ page: data.page - 1 }, false);
              }}
            />
            <span className="tabular-nums">
              {data.page + 1} / {Math.max(1, data.totalPages)}
            </span>
            <Button
              size="sm"
              aria-label="Next page"
              disabled={data.page + 1 >= data.totalPages}
              icon={<ChevronRight className="size-4" />}
              onClick={() => {
                update({ page: data.page + 1 }, false);
              }}
            />
          </div>
        </div>
      )}
    </section>
  );
}
