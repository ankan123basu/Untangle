import type { DeniedAction, TicketAction, TicketStatus } from '@/lib/api/types';

/**
 * Presentation metadata for ticket actions. The server decides WHICH actions are allowed
 * (allowedActions); this file only knows how to label them and what each one needs from the user
 * (a resolution note, a reason). Keep it free of business rules.
 */
export type ActionInput = 'none' | 'note' | 'reason';

export interface TransitionMeta {
  kind: 'transition';
  target: TicketStatus;
  label: string;
  input: ActionInput | ((from: TicketStatus) => ActionInput);
  tone?: 'primary' | 'danger';
}

export const TRANSITIONS: Partial<Record<TicketAction, TransitionMeta>> = {
  START_PROGRESS: {
    kind: 'transition',
    target: 'IN_PROGRESS',
    label: 'Start progress',
    input: 'none',
    tone: 'primary',
  },
  RELEASE: { kind: 'transition', target: 'OPEN', label: 'Release to queue', input: 'none' },
  RESOLVE: {
    kind: 'transition',
    target: 'RESOLVED',
    label: 'Resolve',
    input: 'note',
    tone: 'primary',
  },
  RETURN_TO_PROGRESS: {
    kind: 'transition',
    target: 'IN_PROGRESS',
    label: 'Back to progress',
    input: 'reason',
  },
  // OPEN → CLOSED is "close as spam/duplicate" and needs a reason; RESOLVED → CLOSED doesn't.
  CLOSE: {
    kind: 'transition',
    target: 'CLOSED',
    label: 'Close',
    input: (from) => (from === 'OPEN' ? 'reason' : 'none'),
  },
};

export function inputFor(meta: TransitionMeta, from: TicketStatus): ActionInput {
  return typeof meta.input === 'function' ? meta.input(from) : meta.input;
}

export const ACTION_LABELS: Record<TicketAction, string> = {
  EDIT: 'Edit',
  ASSIGN: 'Assign',
  SELF_ASSIGN: 'Assign to me',
  START_PROGRESS: 'Start progress',
  RELEASE: 'Release to queue',
  RESOLVE: 'Resolve',
  RETURN_TO_PROGRESS: 'Back to progress',
  CLOSE: 'Close',
  REOPEN: 'Reopen',
  COMMENT: 'Comment',
  ESCALATE: 'Escalate',
};

/** Why a button is disabled, in words a support agent understands. */
export function deniedReason(denied: DeniedAction | undefined): string | undefined {
  if (!denied) return undefined;
  switch (denied.reason) {
    case 'TICKET_CLOSED':
      return 'The ticket is closed. A lead can reopen it.';
    case 'ACCESS_DENIED':
      return 'Your role can’t do this on this ticket.';
    case 'INVALID_STATUS_TRANSITION':
      return 'Not possible from the current status.';
    case 'ASSIGNEE_REQUIRED':
      return 'Assign someone first.';
    default:
      return 'Not available right now.';
  }
}

export const STATUS_FLOW: readonly TicketStatus[] = ['OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED'];

/** Which action moves a ticket between two columns (used by the Kanban board). */
export function actionForMove(from: TicketStatus, to: TicketStatus): TicketAction | undefined {
  const key = `${from}>${to}`;
  const map: Record<string, TicketAction> = {
    'OPEN>IN_PROGRESS': 'START_PROGRESS',
    'IN_PROGRESS>OPEN': 'RELEASE',
    'IN_PROGRESS>RESOLVED': 'RESOLVE',
    'RESOLVED>IN_PROGRESS': 'RETURN_TO_PROGRESS',
    'RESOLVED>CLOSED': 'CLOSE',
    'OPEN>CLOSED': 'CLOSE',
    'CLOSED>OPEN': 'REOPEN',
  };
  return map[key];
}

/** SLA state for colouring: breached, at risk (under 25% of the target left), or on track. */
export type SlaState = 'ok' | 'risk' | 'breached' | 'stopped';

export function slaState(input: {
  status: TicketStatus;
  slaBreached: boolean;
  slaDueAt: string;
  slaStartedAt?: string;
  now?: number;
}): SlaState {
  if (input.status === 'RESOLVED' || input.status === 'CLOSED') return 'stopped';
  const now = input.now ?? Date.now();
  const due = Date.parse(input.slaDueAt);
  if (input.slaBreached || due <= now) return 'breached';
  const start = input.slaStartedAt ? Date.parse(input.slaStartedAt) : undefined;
  const total = start !== undefined ? due - start : undefined;
  const left = due - now;
  if (total !== undefined && total > 0 ? left / total < 0.25 : left < 60 * 60 * 1000) return 'risk';
  return 'ok';
}
