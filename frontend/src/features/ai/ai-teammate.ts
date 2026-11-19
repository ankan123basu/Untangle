import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api/client';
import type { AiProposal, AiProposalEnvelope, AiTeammateState } from '@/lib/api/types';
import { queryKeys } from '@/lib/query-client';

/** Who the AI teammate is, and whether it can take work right now (ADR-028). */
export function useAiTeammate() {
  return useQuery({
    queryKey: queryKeys.ai.teammate,
    queryFn: async () => (await api.get<AiTeammateState>('/ai/teammate')).data,
    staleTime: 60_000,
  });
}

/**
 * The AI's latest proposal for a ticket. Polls quickly while the AI is working (it answers in a
 * few seconds) and slowly otherwise.
 */
export function useAiProposal(ticketId: string, aiWorking: boolean) {
  return useQuery({
    queryKey: queryKeys.ai.proposal(ticketId),
    queryFn: async () =>
      (await api.get<AiProposalEnvelope>(`/tickets/${ticketId}/ai-proposal`)).data.proposal,
    refetchInterval: aiWorking ? 2_500 : 20_000,
  });
}

/** Approve (resolve with the AI's note) or take the ticket over. Leads only; the server checks. */
export function useAiDecision(ticketId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: async (v: { decision: 'approve' | 'take-over'; version: number }) =>
      (
        await api.post<AiProposal>(`/tickets/${ticketId}/ai-proposal/${v.decision}`, {
          version: v.version,
        })
      ).data,
    onSuccess: (proposal) => {
      client.setQueryData(queryKeys.ai.proposal(ticketId), proposal);
      void client.invalidateQueries({ queryKey: queryKeys.tickets.detail(ticketId) });
      void client.invalidateQueries({ queryKey: queryKeys.tickets.timeline(ticketId) });
      void client.invalidateQueries({ queryKey: ['tickets', 'list'] });
      void client.invalidateQueries({ queryKey: queryKeys.dashboard.all });
    },
  });
}
