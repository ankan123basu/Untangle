import { useState, type DragEvent } from 'react';
import { Link } from 'react-router';
import { useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Select } from '@/components/ui/field';
import { ErrorState, Skeleton } from '@/components/ui/feedback';
import { api } from '@/lib/api/client';
import { ApiError, messageFor } from '@/lib/api/problem';
import {
  STATUSES,
  type TicketDetail,
  type TicketStatus,
  type TicketSummary,
} from '@/lib/api/types';
import { queryKeys } from '@/lib/query-client';
import { cn, humanize } from '@/lib/utils';
import { EscalationBadge, PriorityBadge, SlaIndicator } from './badges';
import { ActionDialog, type ActionRequest } from './detail/action-dialog';
import { actionForMove, deniedReason, inputFor, TRANSITIONS } from './ticket-meta';
import { useTicketList } from './ticket-api';
import { celebrate } from './celebrate';
import { BotBadge } from '@/components/ui/bot-badge';

const MIME = 'application/x-untangle-ticket';

function Column({
  status,
  dragging,
  onDropTicket,
  onMove,
  onDragChange,
}: {
  status: TicketStatus;
  dragging: TicketSummary | null;
  onDropTicket: (to: TicketStatus) => void;
  onMove: (ticket: TicketSummary, to: TicketStatus) => void;
  onDragChange: (ticket: TicketSummary | null) => void;
}) {
  const [over, setOver] = useState(false);
  const list = useTicketList({
    status: [status],
    sort: status === 'CLOSED' ? 'updatedAt,desc' : 'priority,desc',
    page: 0,
    size: status === 'CLOSED' ? 20 : 50,
  });
  const droppable = dragging !== null && dragging.status !== status;

  const allowDrop = (e: DragEvent) => {
    if (!droppable) return;
    e.preventDefault();
    e.dataTransfer.dropEffect = 'move';
    setOver(true);
  };

  return (
    <section
      aria-labelledby={`col-${status}`}
      onDragOver={allowDrop}
      onDragLeave={() => {
        setOver(false);
      }}
      onDrop={(e) => {
        e.preventDefault();
        setOver(false);
        onDropTicket(status);
      }}
      className={cn(
        'flex min-h-64 w-[82vw] max-w-80 shrink-0 snap-start flex-col rounded-[12px] border-2 border-line bg-paper-2 transition-colors md:w-auto md:max-w-none md:shrink',
        over && 'bg-brand/30',
      )}
    >
      <h2
        id={`col-${status}`}
        className="flex items-center justify-between border-b-2 border-line px-3 py-2 font-display font-bold"
      >
        {humanize(status)}
        <span className="rounded-sm border-2 border-line bg-surface px-1.5 text-xs tabular-nums">
          {list.data?.totalElements ?? '…'}
        </span>
      </h2>
      <ul className="flex flex-1 flex-col gap-2 p-2">
        {list.isError && <ErrorState error={list.error} />}
        {!list.data && !list.isError && (
          <>
            <Skeleton className="h-24" />
            <Skeleton className="h-24" />
          </>
        )}
        {list.data?.content.map((t) => (
          <BoardCard key={t.id} ticket={t} onMove={onMove} onDragChange={onDragChange} />
        ))}
      </ul>
    </section>
  );
}

function BoardCard({
  ticket,
  onMove: move,
  onDragChange,
}: {
  ticket: TicketSummary;
  onMove: (ticket: TicketSummary, to: TicketStatus) => void;
  onDragChange: (ticket: TicketSummary | null) => void;
}) {
  return (
    <li
      draggable
      onDragStart={(e) => {
        e.dataTransfer.setData(MIME, ticket.id);
        e.dataTransfer.effectAllowed = 'move';
        onDragChange(ticket);
      }}
      onDragEnd={() => {
        onDragChange(null);
      }}
      className="cursor-grab rounded-md border-2 border-line bg-surface p-3 shadow-hard-sm active:cursor-grabbing"
    >
      <div className="flex items-center justify-between gap-2">
        <Link
          to={`/tickets/${ticket.id}`}
          className="font-mono text-xs font-bold text-accent hover:underline"
        >
          {ticket.ticketNumber}
        </Link>
        <PriorityBadge priority={ticket.priority} />
      </div>
      <p className="mt-1 line-clamp-2 text-sm font-semibold">{ticket.subject}</p>
      <p className="mt-1 text-xs text-muted">
        {ticket.assignee?.fullName ?? 'Unassigned'} {ticket.assignee?.ai && <BotBadge />}
      </p>
      <div className="mt-2 flex flex-wrap items-center justify-between gap-2">
        <SlaIndicator
          status={ticket.status}
          slaDueAt={ticket.slaDueAt}
          slaBreached={ticket.slaBreached}
          tick={30_000}
          className="text-xs"
        />
        <EscalationBadge level={ticket.escalationLevel} />
      </div>
      {/* Keyboard and screen-reader alternative to dragging. */}
      <Select
        aria-label={`Move ${ticket.ticketNumber} to`}
        className="mt-2 h-7 border-line-soft bg-transparent py-0 text-xs text-muted shadow-none hover:border-line"
        value=""
        onChange={(e) => {
          if (e.target.value) move(ticket, e.target.value as TicketStatus);
        }}
      >
        <option value="">Move to…</option>
        {STATUSES.filter((s) => s !== ticket.status && actionForMove(ticket.status, s)).map((s) => (
          <option key={s} value={s}>
            {humanize(s)}
          </option>
        ))}
      </Select>
    </li>
  );
}

/**
 * Screen 5 (bonus, §12.5): the queue as columns. A drop asks the server what's allowed for this
 * ticket (allowedActions) before moving; a move that isn't allowed "snaps back" (nothing changes)
 * with a toast saying why. Moves that need a note or reason open the same dialog as the detail page.
 */
export default function BoardPage() {
  const client = useQueryClient();
  const [dragging, setDragging] = useState<TicketSummary | null>(null);
  const [request, setRequest] = useState<ActionRequest | null>(null);

  const refresh = () => {
    void client.invalidateQueries({ queryKey: queryKeys.tickets.all });
    void client.invalidateQueries({ queryKey: queryKeys.dashboard.all });
  };

  const move = async (ticket: TicketSummary, to: TicketStatus) => {
    const action = actionForMove(ticket.status, to);
    if (!action) {
      toast.error(`Can’t move from ${humanize(ticket.status)} to ${humanize(to)}.`);
      return;
    }
    let detail: TicketDetail;
    try {
      detail = (await api.get<TicketDetail>(`/tickets/${ticket.id}`)).data;
    } catch (error) {
      toast.error(error instanceof ApiError ? messageFor(error) : 'Could not load the ticket.');
      return;
    }
    if (!detail.allowedActions.includes(action)) {
      const why =
        deniedReason(detail.deniedActions.find((d) => d.action === action)) ?? 'Not allowed.';
      toast.error(`${ticket.ticketNumber}: ${why}`);
      return;
    }
    const perform = async (text?: string) => {
      try {
        if (action === 'REOPEN') {
          await api.post(`/tickets/${ticket.id}/reopen`, { reason: text, version: detail.version });
        } else {
          const meta = TRANSITIONS[action];
          if (!meta) return;
          const input = inputFor(meta, detail.status);
          await api.post(`/tickets/${ticket.id}/transitions`, {
            targetStatus: meta.target,
            version: detail.version,
            ...(input === 'note' ? { note: text } : {}),
            ...(input === 'reason' ? { reason: text } : {}),
          });
        }
        if (to === 'RESOLVED') celebrate();
        toast.success(`${ticket.ticketNumber} → ${humanize(to)}`);
        refresh();
      } catch (error) {
        toast.error(error instanceof ApiError ? messageFor(error) : 'The move failed.');
        refresh();
        throw error;
      }
    };
    const meta = TRANSITIONS[action];
    const input = action === 'REOPEN' ? 'reason' : meta ? inputFor(meta, detail.status) : 'none';
    if (input === 'none') {
      await perform().catch(() => undefined);
    } else {
      setRequest({
        title: `${ticket.ticketNumber}: ${humanize(to)}`,
        description:
          input === 'note' ? 'Explain what fixed it.' : 'Give a short reason for this move.',
        input,
        confirmLabel: `Move to ${humanize(to)}`,
        onConfirm: (text) => perform(text),
      });
    }
  };

  return (
    <div className="flex flex-col gap-4">
      <div>
        <h1 className="font-display text-4xl font-bold tracking-tight">
          <span className="highlight">Board</span>
        </h1>
        <p className="text-muted">
          <span className="hidden md:inline">
            Drag a card to change its status, or use “Move to…”.
          </span>
          <span className="md:hidden">
            Swipe sideways for more columns. Use “Move to…” to change a status.
          </span>
        </p>
      </div>
      <div className="-mx-4 flex snap-x snap-mandatory scroll-px-4 gap-4 overflow-x-auto px-4 pb-2 md:mx-0 md:grid md:snap-none md:grid-cols-4 md:overflow-visible md:px-0">
        {STATUSES.map((status) => (
          <Column
            key={status}
            status={status}
            dragging={dragging}
            onDropTicket={(to) => {
              if (dragging) void move(dragging, to);
              setDragging(null);
            }}
            onMove={(t, to) => void move(t, to)}
            onDragChange={setDragging}
          />
        ))}
      </div>
      <ActionDialog
        request={request}
        onClose={() => {
          setRequest(null);
        }}
      />
    </div>
  );
}
