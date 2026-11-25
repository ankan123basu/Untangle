import { QueryClient } from '@tanstack/react-query';
import { ApiError } from './api/problem';

/**
 * Server state lives in TanStack Query. Client errors (4xx) are never retried: asking again won't
 * fix a validation error or a 403. Network blips and 5xx get two quick retries.
 */
export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 15_000,
        refetchOnWindowFocus: true,
        retry: (failureCount, error) => {
          if (error instanceof ApiError && error.status >= 400 && error.status < 500) return false;
          return failureCount < 2;
        },
      },
      mutations: { retry: false },
    },
  });
}

/** Query-key factory: one place that knows every cache key (§12.6). */
export const queryKeys = {
  me: ['me'] as const,
  tickets: {
    all: ['tickets'] as const,
    list: (params: Record<string, unknown>) => ['tickets', 'list', params] as const,
    detail: (id: string) => ['tickets', 'detail', id] as const,
    timeline: (id: string) => ['tickets', 'timeline', id] as const,
  },
  dashboard: {
    all: ['dashboard'] as const,
    summary: ['dashboard', 'summary'] as const,
    trends: (days: number) => ['dashboard', 'trends', days] as const,
    workload: ['dashboard', 'workload'] as const,
    performance: (days: number) => ['dashboard', 'performance', days] as const,
  },
  agents: {
    options: ['agents', 'options'] as const,
    admin: ['agents', 'admin'] as const,
  },
  autoAssign: ['auto-assign'] as const,
  partners: ['partners'] as const,
  deliveries: (params: Record<string, unknown>) => ['deliveries', params] as const,
  notifications: {
    all: ['notifications'] as const,
    unread: ['notifications', 'unread'] as const,
    list: ['notifications', 'list'] as const,
  },
  ai: {
    status: ['ai', 'status'] as const,
    teammate: ['ai', 'teammate'] as const,
    proposal: (ticketId: string) => ['ai', 'proposal', ticketId] as const,
  },
};
