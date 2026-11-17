import { useMutation, useQuery, useQueryClient, keepPreviousData } from '@tanstack/react-query';
import { api } from '@/lib/api/client';
import type {
  AgentOption,
  AiStatus,
  TicketDetail,
  TicketPage,
  TicketStatus,
  TimelineEntry,
  Workload,
} from '@/lib/api/types';
import { queryKeys } from '@/lib/query-client';
import { useSession } from '@/features/auth/session-store';
import type { TicketQuery } from './filters';

/** Server-state hooks for tickets. Components never call axios directly. */

export function useTicketList(query: TicketQuery) {
  return useQuery({
    queryKey: queryKeys.tickets.list(query as unknown as Record<string, unknown>),
    queryFn: async ({ signal }) =>
      (
        await api.get<TicketPage>('/tickets', {
          params: query,
          signal,
          paramsSerializer: { indexes: null },
        })
      ).data,
    placeholderData: keepPreviousData,
  });
}

export function useTicket(id: string) {
  return useQuery({
    queryKey: queryKeys.tickets.detail(id),
    queryFn: async ({ signal }) => (await api.get<TicketDetail>(`/tickets/${id}`, { signal })).data,
  });
}

export function useTimeline(id: string) {
  return useQuery({
    queryKey: queryKeys.tickets.timeline(id),
    queryFn: async ({ signal }) =>
      (await api.get<TimelineEntry[]>(`/tickets/${id}/comments`, { signal })).data,
  });
}

export function useAgentOptions() {
  return useQuery({
    queryKey: queryKeys.agents.options,
    queryFn: async () => (await api.get<AgentOption[]>('/agents')).data,
    staleTime: 5 * 60_000,
  });
}

export function useWorkload(enabled = true) {
  return useQuery({
    queryKey: queryKeys.dashboard.workload,
    queryFn: async () => (await api.get<Workload[]>('/dashboard/agents-workload')).data,
    enabled,
  });
}

export function useAiStatus() {
  return useQuery({
    queryKey: queryKeys.ai.status,
    queryFn: async () => (await api.get<AiStatus>('/ai/status')).data,
    staleTime: 5 * 60_000,
  });
}

/** Every ticket mutation returns the fresh detail; we put it in the cache and refresh lists. */
function useTicketMutation<V>(id: string, request: (vars: V) => Promise<TicketDetail>) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: request,
    onSuccess: (detail) => {
      client.setQueryData(queryKeys.tickets.detail(id), detail);
      void client.invalidateQueries({ queryKey: queryKeys.tickets.timeline(id) });
      void client.invalidateQueries({ queryKey: ['tickets', 'list'] });
      void client.invalidateQueries({ queryKey: queryKeys.dashboard.all });
    },
  });
}

export function useTransition(id: string) {
  return useTicketMutation(
    id,
    async (body: { targetStatus: TicketStatus; note?: string; reason?: string; version: number }) =>
      (await api.post<TicketDetail>(`/tickets/${id}/transitions`, body)).data,
  );
}

export function useReopen(id: string) {
  return useTicketMutation(
    id,
    async (body: { reason: string; version: number }) =>
      (await api.post<TicketDetail>(`/tickets/${id}/reopen`, body)).data,
  );
}

export function useEscalate(id: string) {
  return useTicketMutation(
    id,
    async (body: { reason: string }) =>
      (await api.post<TicketDetail>(`/tickets/${id}/escalate`, body)).data,
  );
}

export function useEdit(id: string) {
  return useTicketMutation(
    id,
    async (body: {
      subject?: string;
      description?: string;
      category?: TicketDetail['category'];
      priority?: TicketDetail['priority'];
      version: number;
    }) => (await api.patch<TicketDetail>(`/tickets/${id}`, body)).data,
  );
}

/**
 * Assignment is optimistic (§12.6): the new assignee shows immediately and rolls back if the
 * server says no.
 */
export function useAssign(id: string) {
  const client = useQueryClient();
  const key = queryKeys.tickets.detail(id);
  return useMutation({
    mutationFn: async (vars: { agent: AgentOption | null; version: number }) =>
      (
        await api.put<TicketDetail>(`/tickets/${id}/assignee`, {
          ...(vars.agent ? { agentId: vars.agent.id } : {}),
          version: vars.version,
        })
      ).data,
    onMutate: async (vars) => {
      await client.cancelQueries({ queryKey: key });
      const previous = client.getQueryData<TicketDetail>(key);
      if (previous) {
        client.setQueryData<TicketDetail>(key, {
          ...previous,
          assignee: vars.agent ? { id: vars.agent.id, fullName: vars.agent.fullName } : null,
        });
      }
      return { previous };
    },
    onError: (_error, _vars, context) => {
      if (context?.previous) client.setQueryData(key, context.previous);
    },
    onSuccess: (detail) => {
      client.setQueryData(key, detail);
    },
    onSettled: () => {
      void client.invalidateQueries({ queryKey: queryKeys.tickets.timeline(id) });
      void client.invalidateQueries({ queryKey: ['tickets', 'list'] });
      void client.invalidateQueries({ queryKey: queryKeys.dashboard.all });
    },
  });
}

/**
 * Comments are optimistic too: the entry appears at once (marked pending) and is removed again if
 * the server rejects it.
 */
export function useAddComment(id: string) {
  const client = useQueryClient();
  const key = queryKeys.tickets.timeline(id);
  return useMutation({
    mutationFn: async (vars: { body: string; type: 'COMMENT' | 'RESOLUTION_NOTE' }) =>
      (await api.post<TimelineEntry>(`/tickets/${id}/comments`, vars)).data,
    onMutate: async (vars) => {
      await client.cancelQueries({ queryKey: key });
      const previous = client.getQueryData<TimelineEntry[]>(key);
      const agent = useSession.getState().agent;
      const optimistic: TimelineEntry = {
        id: `pending-${String(Date.now())}`,
        type: vars.type,
        body: vars.body,
        author: agent ? { id: agent.id, fullName: agent.fullName } : null,
        metadata: null,
        createdAt: new Date().toISOString(),
      };
      client.setQueryData<TimelineEntry[]>(key, [...(previous ?? []), optimistic]);
      return { previous };
    },
    onError: (_error, _vars, context) => {
      client.setQueryData(key, context?.previous);
    },
    onSettled: () => {
      void client.invalidateQueries({ queryKey: key });
      // The first agent comment sets first_response_at on the ticket.
      void client.invalidateQueries({ queryKey: queryKeys.tickets.detail(id) });
    },
  });
}
