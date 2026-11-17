import { Navigate, useParams } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api/client';
import type { TicketDetail } from '@/lib/api/types';
import { ErrorState, Skeleton } from '@/components/ui/feedback';

/** /tickets/by-number/UNT-000042 → /tickets/{id} (the command palette's "jump to"). */
export default function TicketByNumber() {
  const { number = '' } = useParams();
  const lookup = useQuery({
    queryKey: ['ticket-by-number', number],
    queryFn: async () =>
      (await api.get<TicketDetail>(`/tickets/by-number/${encodeURIComponent(number)}`)).data,
  });
  if (lookup.data) return <Navigate to={`/tickets/${lookup.data.id}`} replace />;
  if (lookup.isError) {
    return (
      <ErrorState title={`${number} not found`} description="Check the number and try again." />
    );
  }
  return <Skeleton className="h-64" />;
}
