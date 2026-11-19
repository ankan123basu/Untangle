import { useState, ViewTransition } from 'react';
import { Link, useParams } from 'react-router';
import { useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, Flame, Lock, Pencil, RotateCcw } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Card, CardBody, CardHeader, CardTitle } from '@/components/ui/card';
import { Dialog } from '@/components/ui/dialog';
import { ErrorState, Skeleton } from '@/components/ui/feedback';
import { Tooltip } from '@/components/ui/tooltip';
import { ApiError, messageFor } from '@/lib/api/problem';
import type { TicketAction, TicketDetail } from '@/lib/api/types';
import { queryKeys } from '@/lib/query-client';
import { ticketTransitionName } from '@/lib/motion';
import { humanize } from '@/lib/utils';
import { EscalationBadge, PriorityBadge, SlaIndicator, SourceBadge, StatusBadge } from './badges';
import { ActionDialog, type ActionRequest } from './detail/action-dialog';
import { AiPanel } from './detail/ai-panel';
import { AiTeammatePanel } from './detail/ai-teammate-panel';
import { Composer } from './detail/composer';
import { DetailsCard } from './detail/details-card';
import { EditDialog } from './detail/edit-dialog';
import { StatusStepper } from './detail/status-stepper';
import { Timeline } from './detail/timeline';
import { ACTION_LABELS, deniedReason, inputFor, TRANSITIONS } from './ticket-meta';
import { useAssign, useEdit, useEscalate, useReopen, useTicket, useTransition } from './ticket-api';
import { celebrate } from './celebrate';

/** Order of the buttons in the action bar. */
const BAR: TicketAction[] = [
  'START_PROGRESS',
  'RESOLVE',
  'RETURN_TO_PROGRESS',
  'RELEASE',
  'CLOSE',
  'ESCALATE',
  'EDIT',
];

/**
 * Screen 3 (§12.5): work a ticket end to end. Every button comes from the server's allowedActions;
 * disabled ones explain why (deniedActions). Each write sends the version we loaded; if someone
 * else changed the ticket meanwhile, the conflict dialog offers to reload.
 */
export default function TicketDetailPage() {
  const { id = '' } = useParams();
  const ticket = useTicket(id);

  if (ticket.isError) {
    const notFound = ticket.error instanceof ApiError && ticket.error.status === 404;
    return (
      <section className="solid-panel">
        <ErrorState
          title={notFound ? 'Ticket not found' : 'Could not load the ticket'}
          error={notFound ? undefined : ticket.error}
          description={
            notFound ? 'It may have been mistyped, or you followed an old link.' : undefined
          }
          onRetry={
            notFound
              ? undefined
              : () => {
                  void ticket.refetch();
                }
          }
        />
      </section>
    );
  }
  if (!ticket.data) {
    return (
      <div
        className="flex flex-col gap-4"
        role="status"
        aria-busy="true"
        aria-label="Loading ticket"
      >
        <Skeleton className="h-36" />
        <div className="grid gap-4 lg:grid-cols-[1fr_340px]">
          <Skeleton className="h-96" />
          <Skeleton className="h-72" />
        </div>
      </div>
    );
  }
  return <TicketWorkspace ticket={ticket.data} />;
}

function TicketWorkspace({ ticket }: { ticket: TicketDetail }) {
  const client = useQueryClient();
  const transition = useTransition(ticket.id);
  const reopen = useReopen(ticket.id);
  const escalate = useEscalate(ticket.id);
  const edit = useEdit(ticket.id);
  const assign = useAssign(ticket.id);

  const [request, setRequest] = useState<ActionRequest | null>(null);
  const [editing, setEditing] = useState(false);
  const [conflict, setConflict] = useState(false);
  const [draft, setDraft] = useState('');

  const allowed = (a: TicketAction) => ticket.allowedActions.includes(a);
  const denied = (a: TicketAction) =>
    deniedReason(ticket.deniedActions.find((d) => d.action === a));
  const busy = transition.isPending || reopen.isPending || escalate.isPending || edit.isPending;

  /** One place that turns a failed write into UI: conflict dialog, or a friendly toast. */
  const fail = (error: unknown): never => {
    if (error instanceof ApiError && error.code === 'VERSION_CONFLICT') setConflict(true);
    else toast.error(error instanceof ApiError ? messageFor(error) : 'Something went wrong.');
    throw error;
  };

  const runTransition = async (action: TicketAction, text?: string) => {
    const meta = TRANSITIONS[action];
    if (!meta) return;
    const input = inputFor(meta, ticket.status);
    try {
      await transition.mutateAsync({
        targetStatus: meta.target,
        version: ticket.version,
        ...(input === 'note' && text ? { note: text } : {}),
        ...(input === 'reason' && text ? { reason: text } : {}),
      });
      if (meta.target === 'RESOLVED') celebrate();
      toast.success(`${ticket.ticketNumber} → ${humanize(meta.target)}`);
    } catch (error) {
      fail(error);
    }
  };

  const onAction = (action: TicketAction) => {
    if (action === 'EDIT') {
      setEditing(true);
      return;
    }
    if (action === 'ESCALATE') {
      setRequest({
        title: 'Escalate ticket',
        description: 'Raises the escalation level and notifies the leads.',
        input: 'reason',
        confirmLabel: 'Escalate',
        tone: 'danger',
        onConfirm: async (reason) => {
          await escalate.mutateAsync({ reason }).catch(fail);
          toast.success('Escalated. The leads have been notified.');
        },
      });
      return;
    }
    const meta = TRANSITIONS[action];
    if (!meta) return;
    const input = inputFor(meta, ticket.status);
    if (input === 'none') {
      void runTransition(action).catch(() => undefined);
      return;
    }
    setRequest({
      title: meta.label,
      description:
        input === 'note'
          ? 'Explain what fixed it. The note is added to the timeline.'
          : action === 'CLOSE'
            ? 'Closing an open ticket means it is spam, a duplicate or invalid. Say which.'
            : 'Say why the ticket needs more work.',
      input,
      confirmLabel: meta.label,
      ...(meta.tone ? { tone: meta.tone } : {}),
      onConfirm: (text) => runTransition(action, text),
    });
  };

  const onReopen = () => {
    setRequest({
      title: `Reopen ${ticket.ticketNumber}`,
      description: 'The SLA clock restarts from now.',
      input: 'reason',
      confirmLabel: 'Reopen',
      onConfirm: async (reason) => {
        await reopen.mutateAsync({ reason, version: ticket.version }).catch(fail);
        toast.success('Ticket reopened');
      },
    });
  };

  const reload = () => {
    setConflict(false);
    void client.invalidateQueries({ queryKey: queryKeys.tickets.detail(ticket.id) });
    void client.invalidateQueries({ queryKey: queryKeys.tickets.timeline(ticket.id) });
  };

  const closed = ticket.status === 'CLOSED';

  return (
    <div className="flex flex-col gap-4">
      <Link
        to="/tickets"
        className="inline-flex w-fit items-center gap-1 text-sm font-semibold text-muted hover:text-ink"
      >
        <ArrowLeft aria-hidden="true" className="size-4" /> All tickets
      </Link>

      {closed && (
        <div
          role="status"
          className="flex flex-wrap items-center justify-between gap-3 rounded-md border-2 border-line bg-status-closed-bg px-4 py-3 text-status-closed-fg"
        >
          <p className="flex items-center gap-2 font-semibold">
            <Lock aria-hidden="true" className="size-4" /> This ticket is closed and read-only.
          </p>
          {allowed('REOPEN') ? (
            <Button size="sm" icon={<RotateCcw className="size-4" />} onClick={onReopen}>
              Reopen
            </Button>
          ) : (
            <p className="text-sm">Only a lead or admin can reopen it.</p>
          )}
        </div>
      )}

      <header className="solid-panel flex flex-col gap-4 p-5">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="min-w-0">
            <ViewTransition name={ticketTransitionName(ticket.id)}>
              <p className="w-fit font-mono text-sm font-bold text-muted">{ticket.ticketNumber}</p>
            </ViewTransition>
            <h1 className="font-display text-2xl font-bold break-words md:text-3xl">
              {ticket.subject}
            </h1>
            <div className="mt-2 flex flex-wrap items-center gap-2">
              <PriorityBadge priority={ticket.priority} />
              <StatusBadge status={ticket.status} />
              <EscalationBadge level={ticket.escalationLevel} />
              <SourceBadge source={ticket.source} />
              <span className="text-sm text-muted">
                {ticket.assignee ? `Assigned to ${ticket.assignee.fullName}` : 'Unassigned'}
              </span>
            </div>
          </div>
          <div className="w-full sm:w-auto sm:text-right">
            <p className="text-xs font-bold tracking-wide text-muted uppercase">SLA</p>
            <SlaIndicator
              status={ticket.status}
              slaDueAt={ticket.slaDueAt}
              slaStartedAt={ticket.slaStartedAt}
              slaBreached={ticket.slaBreached}
              className="text-base"
            />
          </div>
        </div>

        <StatusStepper status={ticket.status} />

        {!closed && !allowed('EDIT') && (
          <p
            role="note"
            className="flex items-center gap-2 rounded-md border-2 border-line-soft bg-paper-2 px-3 py-2 text-sm"
          >
            <Lock aria-hidden="true" className="size-4 shrink-0" />
            {ticket.assignee
              ? `View only: this is ${ticket.assignee.fullName}’s ticket. You can comment and escalate; editing and status changes are for the assignee or a lead.`
              : 'View only: this ticket is unassigned. Assign it to yourself to work on it.'}
          </p>
        )}

        {!closed && (
          <div
            role="toolbar"
            aria-label="Ticket actions"
            className="flex flex-wrap gap-2 border-t-2 border-line-soft pt-4"
          >
            {BAR.map((action) => {
              const can = allowed(action);
              const why = ticket.deniedActions.find((d) => d.action === action);
              // Hide what this person may never do here (their role/ownership) and moves that make
              // no sense from this status. Only "needs a step first" stays, disabled with the reason.
              if (
                !can &&
                (!why ||
                  why.reason === 'INVALID_STATUS_TRANSITION' ||
                  why.reason === 'ACCESS_DENIED')
              )
                return null;
              const reason = deniedReason(why);
              const meta = TRANSITIONS[action];
              const icon =
                action === 'EDIT' ? (
                  <Pencil className="size-4" />
                ) : action === 'ESCALATE' ? (
                  <Flame className="size-4" />
                ) : undefined;
              return (
                <Tooltip key={action} content={can ? undefined : reason}>
                  <Button
                    size="sm"
                    variant={
                      can && meta?.tone === 'primary'
                        ? 'primary'
                        : action === 'ESCALATE'
                          ? 'danger'
                          : 'secondary'
                    }
                    aria-disabled={!can || busy}
                    className={!can ? 'cursor-not-allowed opacity-50' : undefined}
                    icon={icon}
                    onClick={() => {
                      if (can && !busy) onAction(action);
                    }}
                  >
                    {ACTION_LABELS[action]}
                  </Button>
                </Tooltip>
              );
            })}
          </div>
        )}
      </header>

      <div className="grid items-start gap-4 lg:grid-cols-[minmax(0,1fr)_340px]">
        <div className="flex min-w-0 flex-col gap-4">
          <AiTeammatePanel ticket={ticket} />
          <Card>
            <CardHeader>
              <CardTitle>Description</CardTitle>
            </CardHeader>
            <CardBody>
              <p className="break-words whitespace-pre-wrap">{ticket.description}</p>
            </CardBody>
          </Card>
          <Card>
            <CardHeader>
              <CardTitle>Timeline</CardTitle>
            </CardHeader>
            <CardBody className="flex flex-col gap-6">
              <Timeline ticketId={ticket.id} />
              {!closed && (
                <Composer
                  ticketId={ticket.id}
                  value={draft}
                  onChange={setDraft}
                  disabledReason={
                    allowed('COMMENT')
                      ? undefined
                      : (denied('COMMENT') ?? 'You can’t comment here.')
                  }
                />
              )}
            </CardBody>
          </Card>
        </div>
        <aside className="flex flex-col gap-4" aria-label="Ticket details">
          <DetailsCard
            ticket={ticket}
            assigning={assign.isPending}
            onAssign={(agent) => {
              assign.mutate(
                { agent, version: ticket.version },
                {
                  onSuccess: () =>
                    toast.success(agent ? `Assigned to ${agent.fullName}` : 'Unassigned'),
                  onError: (error) => {
                    try {
                      fail(error);
                    } catch {
                      /* shown */
                    }
                  },
                },
              );
            }}
          />
          {!closed && <AiPanel ticketId={ticket.id} onUseDraft={setDraft} />}
        </aside>
      </div>

      <ActionDialog
        request={request}
        onClose={() => {
          setRequest(null);
        }}
      />
      <EditDialog
        open={editing}
        ticket={ticket}
        onClose={() => {
          setEditing(false);
        }}
        onSave={async (changes) => {
          await edit.mutateAsync({ ...changes, version: ticket.version }).catch(fail);
          toast.success('Ticket updated');
        }}
      />
      <Dialog
        open={conflict}
        onClose={() => {
          setConflict(false);
        }}
        title="This ticket changed"
        description="Someone else updated it while you were looking. Load their changes, then try again."
        footer={
          <Button variant="primary" onClick={reload}>
            Load latest
          </Button>
        }
      >
        <p className="text-sm">Nothing you did was saved, so nothing was overwritten.</p>
      </Dialog>
    </div>
  );
}
