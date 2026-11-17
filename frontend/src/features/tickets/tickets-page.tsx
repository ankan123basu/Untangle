import { Link } from 'react-router';
import { Columns3, Plus } from 'lucide-react';
import { useCan } from '@/features/auth/session-store';
import { TicketBrowser } from './ticket-browser';

/** /tickets: the full ticket list with URL-synced filters. */
export default function TicketsPage() {
  const canCreate = useCan('TICKET_CREATE');
  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="font-display text-3xl font-bold">Tickets</h1>
          <p className="text-muted">Filter, search and share the exact view with a link.</p>
        </div>
        <div className="flex gap-2">
          <Link
            to="/board"
            className="inline-flex h-10 items-center gap-2 rounded-md border-2 border-line bg-surface px-4 text-sm font-semibold shadow-hard-sm"
          >
            <Columns3 aria-hidden="true" className="size-4" /> Board
          </Link>
          {canCreate && (
            <Link
              to="/tickets/new"
              className="inline-flex h-10 items-center gap-2 rounded-md border-2 border-line bg-brand px-4 text-sm font-semibold text-on-brand shadow-hard-sm"
            >
              <Plus aria-hidden="true" className="size-4" /> New ticket
            </Link>
          )}
        </div>
      </div>
      <TicketBrowser title="All tickets" />
    </div>
  );
}
