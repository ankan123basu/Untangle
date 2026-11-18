import { Link, useSearchParams } from 'react-router';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { ChevronLeft, ChevronRight } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { EmptyState, ErrorState, Skeleton } from '@/components/ui/feedback';
import { Input, Select } from '@/components/ui/field';
import { api } from '@/lib/api/client';
import type { DeliveryPage, DeliveryView, PartnerView } from '@/lib/api/types';
import { queryKeys } from '@/lib/query-client';
import { cn, formatDateTime, humanize } from '@/lib/utils';

const OUTCOMES: DeliveryView['outcome'][] = [
  'CREATED',
  'DUPLICATE',
  'REJECTED_SIGNATURE',
  'REJECTED_TIMESTAMP',
  'REJECTED_UNKNOWN_PARTNER',
  'REJECTED_VALIDATION',
  'REJECTED_TOO_LARGE',
  'RATE_LIMITED',
];

function outcomeClass(outcome: DeliveryView['outcome']): string {
  if (outcome === 'CREATED') return 'bg-status-resolved-bg text-status-resolved-fg';
  if (outcome === 'DUPLICATE') return 'bg-status-progress-bg text-status-progress-fg';
  if (outcome === 'RATE_LIMITED') return 'bg-prio-high-bg text-prio-high-fg';
  return 'bg-prio-critical-bg text-prio-critical-fg';
}

/** datetime-local value (local time, no zone) → ISO instant for the API. */
function toIso(local: string | null): string | undefined {
  if (!local) return undefined;
  const ms = Date.parse(local);
  return Number.isNaN(ms) ? undefined : new Date(ms).toISOString();
}

/**
 * Screen 7 (§12.5): every webhook call and what happened to it, for debugging partner
 * integrations. Filters are in the URL so a link can be shared with the partner's engineers.
 */
export default function WebhookLogsPage() {
  const [params, setParams] = useSearchParams();
  const partnerId = params.get('partnerId') ?? '';
  const outcomeParam = params.get('outcome') ?? '';
  const outcome = OUTCOMES.includes(outcomeParam as DeliveryView['outcome']) ? outcomeParam : '';
  const from = params.get('from') ?? '';
  const to = params.get('to') ?? '';
  const page = Math.max(0, Number(params.get('page')) || 0);

  const set = (key: string, value: string) => {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value);
    else next.delete(key);
    if (key !== 'page') next.delete('page');
    setParams(next);
  };

  const partners = useQuery({
    queryKey: queryKeys.partners,
    queryFn: async () => (await api.get<PartnerView[]>('/admin/partners')).data,
  });
  const query = {
    ...(partnerId ? { partnerId } : {}),
    ...(outcome ? { outcome } : {}),
    ...(toIso(from) ? { from: toIso(from) } : {}),
    ...(toIso(to) ? { to: toIso(to) } : {}),
    page,
    size: 50,
  };
  const deliveries = useQuery({
    queryKey: queryKeys.deliveries(query),
    queryFn: async () =>
      (await api.get<DeliveryPage>('/admin/webhook-deliveries', { params: query })).data,
    placeholderData: keepPreviousData,
    refetchInterval: 15_000,
  });
  const data = deliveries.data;

  return (
    <div className="flex flex-col gap-4">
      <div>
        <h1 className="font-display text-4xl font-bold tracking-tight">
          <span className="highlight">Webhook logs</span>
        </h1>
        <p className="text-muted">
          Every partner call, accepted or rejected, newest first. Kept for 30 days.
        </p>
      </div>
      <section className="solid-panel overflow-hidden">
        <div className="grid gap-2 border-b-2 border-line p-4 sm:grid-cols-2 lg:grid-cols-4">
          <Select
            aria-label="Partner"
            value={partnerId}
            onChange={(e) => {
              set('partnerId', e.target.value);
            }}
          >
            <option value="">All partners</option>
            {partners.data?.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name} ({p.code})
              </option>
            ))}
          </Select>
          <Select
            aria-label="Outcome"
            value={outcome}
            onChange={(e) => {
              set('outcome', e.target.value);
            }}
          >
            <option value="">All outcomes</option>
            {OUTCOMES.map((o) => (
              <option key={o} value={o}>
                {humanize(o)}
              </option>
            ))}
          </Select>
          <Input
            type="datetime-local"
            aria-label="From"
            value={from}
            onChange={(e) => {
              set('from', e.target.value);
            }}
          />
          <Input
            type="datetime-local"
            aria-label="To"
            value={to}
            onChange={(e) => {
              set('to', e.target.value);
            }}
          />
        </div>

        {deliveries.isError ? (
          <ErrorState error={deliveries.error} onRetry={() => void deliveries.refetch()} />
        ) : !data ? (
          <div className="flex flex-col gap-3 p-4">
            <Skeleton className="h-10" />
            <Skeleton className="h-10" />
            <Skeleton className="h-10" />
          </div>
        ) : data.content.length === 0 ? (
          <EmptyState
            title="No deliveries"
            description="Calls from partner systems will appear here as they arrive."
          />
        ) : (
          <div className={cn('overflow-x-auto', deliveries.isPlaceholderData && 'opacity-60')}>
            <table className="stack-table w-full text-left text-sm md:min-w-[900px]">
              <caption className="sr-only">Webhook deliveries</caption>
              <thead className="bg-paper-2 text-xs font-bold text-muted uppercase">
                <tr>
                  <th scope="col" className="px-4 py-2">
                    Received
                  </th>
                  <th scope="col" className="py-2 pr-3">
                    Partner
                  </th>
                  <th scope="col" className="py-2 pr-3">
                    Outcome
                  </th>
                  <th scope="col" className="py-2 pr-3">
                    Event id
                  </th>
                  <th scope="col" className="py-2 pr-3">
                    Reason
                  </th>
                  <th scope="col" className="py-2 pr-3">
                    Ticket
                  </th>
                  <th scope="col" className="py-2 pr-4">
                    Source IP
                  </th>
                </tr>
              </thead>
              <tbody>
                {data.content.map((d) => (
                  <tr key={d.id} className="border-t-2 border-line-soft align-top">
                    <td data-label="Received" className="px-4 py-2 whitespace-nowrap tabular-nums">
                      {formatDateTime(d.receivedAt)}
                    </td>
                    <td data-label="Partner" className="py-2 pr-3 font-mono text-xs">
                      {d.partnerCode ?? '—'}
                    </td>
                    <td data-label="Outcome" className="py-2 pr-3">
                      <Badge className={outcomeClass(d.outcome)}>{humanize(d.outcome)}</Badge>
                    </td>
                    <td
                      data-label="Event id"
                      className="max-w-48 truncate py-2 pr-3 font-mono text-xs"
                      title={d.eventId ?? undefined}
                    >
                      {d.eventId ?? '—'}
                    </td>
                    <td data-label="Reason" className="max-w-64 py-2 pr-3 text-xs break-words">
                      {d.reason ?? '—'}
                    </td>
                    <td data-label="Ticket" className="py-2 pr-3">
                      {d.ticketId && d.ticketNumber ? (
                        <Link
                          to={`/tickets/${d.ticketId}`}
                          className="font-mono text-xs font-bold text-accent underline"
                        >
                          {d.ticketNumber}
                        </Link>
                      ) : (
                        '—'
                      )}
                    </td>
                    <td data-label="Source IP" className="py-2 pr-4 font-mono text-xs">
                      {d.sourceIp ?? '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {data && data.totalPages > 1 && (
          <div className="flex items-center justify-end gap-2 border-t-2 border-line px-4 py-3 text-sm">
            <Button
              size="sm"
              aria-label="Previous page"
              disabled={page === 0}
              icon={<ChevronLeft className="size-4" />}
              onClick={() => {
                set('page', String(page - 1));
              }}
            />
            <span className="tabular-nums">
              {page + 1} / {data.totalPages}
            </span>
            <Button
              size="sm"
              aria-label="Next page"
              disabled={page + 1 >= data.totalPages}
              icon={<ChevronRight className="size-4" />}
              onClick={() => {
                set('page', String(page + 1));
              }}
            />
          </div>
        )}
      </section>
    </div>
  );
}
