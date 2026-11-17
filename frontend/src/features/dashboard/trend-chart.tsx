import { useQuery } from '@tanstack/react-query';
import {
  CartesianGrid,
  Legend,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip as ChartTooltip,
  XAxis,
  YAxis,
} from 'recharts';
import { ErrorState, Skeleton } from '@/components/ui/feedback';
import { api } from '@/lib/api/client';
import type { DayTrend } from '@/lib/api/types';
import { queryKeys } from '@/lib/query-client';

function useTrends(days: number) {
  return useQuery({
    queryKey: queryKeys.dashboard.trends(days),
    queryFn: async () =>
      (await api.get<DayTrend[]>('/dashboard/trends', { params: { days } })).data,
    refetchInterval: 5 * 60_000,
  });
}

/**
 * 14-day created vs resolved, with the same numbers available as a table. Only the chart body is
 * lazy; the card frame and title render with the page so they don't delay the first paint.
 */
export default function TrendChart() {
  const trends = useTrends(14);
  return (
    <>
      {trends.isError ? (
        <ErrorState error={trends.error} onRetry={() => void trends.refetch()} />
      ) : !trends.data ? (
        <Skeleton className="h-64" />
      ) : (
        <>
          <div className="h-64" aria-hidden="true">
            <ResponsiveContainer width="100%" height="100%">
              <LineChart
                // Hidden from assistive tech (the table below carries the data), so it must
                // not be focusable either.
                accessibilityLayer={false}
                data={trends.data}
                margin={{ top: 8, right: 8, bottom: 0, left: -16 }}
              >
                <CartesianGrid stroke="var(--ut-line-soft)" strokeDasharray="4 4" />
                <XAxis
                  dataKey="date"
                  tickFormatter={(d: string) => d.slice(5)}
                  stroke="var(--ut-muted)"
                  fontSize={12}
                />
                <YAxis allowDecimals={false} stroke="var(--ut-muted)" fontSize={12} />
                <ChartTooltip
                  contentStyle={{
                    background: 'var(--ut-surface)',
                    border: '2px solid var(--ut-line)',
                    borderRadius: 8,
                    boxShadow: 'var(--ut-shadow-hard-sm)',
                    color: 'var(--ut-ink)',
                  }}
                />
                <Legend />
                <Line
                  type="monotone"
                  dataKey="created"
                  name="Created"
                  stroke="var(--ut-accent)"
                  strokeWidth={3}
                  dot={false}
                />
                <Line
                  type="monotone"
                  dataKey="resolved"
                  name="Resolved"
                  stroke="var(--ut-success)"
                  strokeWidth={3}
                  strokeDasharray="6 3"
                  dot={false}
                />
              </LineChart>
            </ResponsiveContainer>
          </div>
          {/* Same data as a table for screen readers and anyone who prefers numbers. */}
          <details className="mt-2 text-sm">
            <summary className="cursor-pointer font-semibold text-muted">Show as table</summary>
            <table className="mt-2 w-full text-left tabular-nums">
              <thead>
                <tr className="text-muted">
                  <th scope="col">Day</th>
                  <th scope="col">Created</th>
                  <th scope="col">Resolved</th>
                </tr>
              </thead>
              <tbody>
                {trends.data.map((d) => (
                  <tr key={d.date}>
                    <td>{d.date}</td>
                    <td>{d.created}</td>
                    <td>{d.resolved}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </details>
        </>
      )}
    </>
  );
}
