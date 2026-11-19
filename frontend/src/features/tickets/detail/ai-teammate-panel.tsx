import { Bot, Check, Hand } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { BotBadge } from '@/components/ui/bot-badge';
import { useAiDecision, useAiProposal, useAiTeammate } from '@/features/ai/ai-teammate';
import { ApiError, messageFor } from '@/lib/api/problem';
import type { TicketDetail } from '@/lib/api/types';
import { formatDateTime, timeAgo } from '@/lib/utils';

/**
 * The AI teammate on a ticket (ADR-028): working, waiting for a lead's decision, or decided. A
 * lead approves the proposal (the ticket is resolved with its note) or takes the ticket over;
 * everyone else sees that it is waiting for a lead. The server decides who may do what
 * (`canDecide`); this only renders it.
 */
export function AiTeammatePanel({ ticket }: { ticket: TicketDetail }) {
  const aiAssigned = Boolean(ticket.assignee?.ai);
  const open = ticket.status === 'OPEN' || ticket.status === 'IN_PROGRESS';
  const proposal = useAiProposal(ticket.id, aiAssigned && open);
  const decide = useAiDecision(ticket.id);
  // The name comes from the AI's agent record ("Unty" by default; admins can rename it).
  const teammate = useAiTeammate();
  const name = teammate.data?.name ?? (ticket.assignee?.ai ? ticket.assignee.fullName : 'Unty');
  const p = proposal.data;
  const pending = p?.status === 'PENDING';
  const working = aiAssigned && open && !pending;
  const decided = p && (p.status === 'APPROVED' || p.status === 'REJECTED');

  if (!working && !pending && !decided) return null;

  const run = (decision: 'approve' | 'take-over') => {
    decide.mutate(
      { decision, version: ticket.version },
      {
        onSuccess: () => {
          toast.success(
            decision === 'approve'
              ? `${ticket.ticketNumber} resolved with ${name}’s note`
              : `You took over ${ticket.ticketNumber}`,
          );
        },
        onError: (e) => {
          toast.error(e instanceof ApiError ? messageFor(e) : 'Something went wrong');
          void proposal.refetch();
        },
      },
    );
  };

  return (
    <section
      aria-labelledby="ai-teammate-title"
      className="sticker rounded-[16px] border-[3px] border-[#111] bg-stk-lilac p-5 shadow-[5px_5px_0_0_#111]"
    >
      <header className="flex flex-wrap items-center gap-2">
        <span className="flex size-9 items-center justify-center rounded-md border-2 border-[#111] bg-white">
          <Bot aria-hidden="true" className="size-5" strokeWidth={2.5} />
        </span>
        <h2 id="ai-teammate-title" className="font-display text-lg font-bold">
          {name}
        </h2>
        <BotBadge />
        <span className="ml-auto text-xs font-semibold text-[#111]/70">
          Proposes · a lead decides
        </span>
      </header>

      {working && (
        <p role="status" className="mt-3 flex items-center gap-2 text-sm font-semibold">
          <span aria-hidden="true" className="flex gap-1">
            {[0, 1, 2].map((i) => (
              <span
                key={i}
                className="size-2 animate-bounce rounded-full bg-[#111]"
                style={{ animationDelay: `${String(i * 150)}ms` }}
              />
            ))}
          </span>
          Reading the ticket and drafting a reply…
        </p>
      )}

      {pending && (
        <div className="mt-4 flex flex-col gap-3">
          <div>
            <p className="flex items-baseline justify-between text-xs font-extrabold tracking-wide uppercase">
              Confidence{' '}
              <span className="font-mono text-sm">{Math.round(p.confidence * 100)}%</span>
            </p>
            <div
              role="meter"
              aria-label="AI confidence"
              aria-valuemin={0}
              aria-valuemax={100}
              aria-valuenow={Math.round(p.confidence * 100)}
              className="mt-1 h-3 overflow-hidden rounded-sm border-2 border-[#111] bg-white"
            >
              <div
                className="h-full bg-[#34d399]"
                style={{ width: `${String(p.confidence * 100)}%` }}
              />
            </div>
          </div>
          <div>
            <p className="text-xs font-extrabold tracking-wide uppercase">
              Proposed resolution note
            </p>
            <p className="mt-1 rounded-md border-2 border-[#111] bg-white p-3 text-sm break-words whitespace-pre-wrap">
              {p.resolutionNote}
            </p>
          </div>
          <p className="text-xs text-[#111]/75">
            Its reply to the customer is on the timeline. Proposed {timeAgo(p.createdAt)} by{' '}
            {p.model}.
          </p>
          {p.canDecide ? (
            <div className="flex flex-wrap gap-2">
              <Button
                variant="primary"
                icon={<Check className="size-4" />}
                loading={decide.isPending && decide.variables.decision === 'approve'}
                disabled={decide.isPending}
                onClick={() => {
                  run('approve');
                }}
              >
                Approve &amp; resolve
              </Button>
              <Button
                icon={<Hand className="size-4" />}
                loading={decide.isPending && decide.variables.decision === 'take-over'}
                disabled={decide.isPending}
                onClick={() => {
                  run('take-over');
                }}
              >
                Take over
              </Button>
            </div>
          ) : (
            <p className="rounded-md border-2 border-dashed border-[#111] bg-white/60 px-3 py-2 text-sm font-semibold">
              Waiting for a lead to approve or take over.
            </p>
          )}
        </div>
      )}

      {decided && !pending && !working && (
        <p className="mt-3 text-sm font-semibold">
          {p.status === 'APPROVED' ? 'Proposal approved' : 'Taken over'} by {p.decidedBy}
          {p.decidedAt && (
            <time dateTime={p.decidedAt} title={formatDateTime(p.decidedAt)}>
              {' '}
              · {timeAgo(p.decidedAt)}
            </time>
          )}
        </p>
      )}
    </section>
  );
}
