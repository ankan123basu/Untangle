import { AlertOctagon, Clock, Flame, Webhook } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import type { Priority, TicketStatus } from '@/lib/api/types';
import { cn, formatDuration, humanize } from '@/lib/utils';
import { useNow } from '@/hooks/use-now';
import { slaState } from './ticket-meta';

const PRIORITY_CLASSES: Record<Priority, string> = {
  LOW: 'bg-prio-low-bg text-prio-low-fg',
  MEDIUM: 'bg-prio-medium-bg text-prio-medium-fg',
  HIGH: 'bg-prio-high-bg text-prio-high-fg',
  CRITICAL: 'bg-prio-critical-bg text-prio-critical-fg animate-pulse-critical',
};

const STATUS_CLASSES: Record<TicketStatus, string> = {
  OPEN: 'bg-status-open-bg text-status-open-fg',
  IN_PROGRESS: 'bg-status-progress-bg text-status-progress-fg',
  RESOLVED: 'bg-status-resolved-bg text-status-resolved-fg',
  CLOSED: 'bg-status-closed-bg text-status-closed-fg',
};

/** Colour is never the only signal: every badge also says its value in words. */
export function PriorityBadge({ priority, className }: { priority: Priority; className?: string }) {
  return <Badge className={cn(PRIORITY_CLASSES[priority], className)}>{humanize(priority)}</Badge>;
}

export function StatusBadge({ status, className }: { status: TicketStatus; className?: string }) {
  return <Badge className={cn(STATUS_CLASSES[status], className)}>{humanize(status)}</Badge>;
}

export function EscalationBadge({ level }: { level: number }) {
  if (level <= 0) return null;
  return (
    <Badge className="bg-danger text-on-danger" title={`Escalation level ${String(level)}`}>
      <Flame aria-hidden="true" className="size-3" />
      Escalated L{level}
    </Badge>
  );
}

export function SourceBadge({ source }: { source: 'MANUAL' | 'WEBHOOK' }) {
  if (source !== 'WEBHOOK') return null;
  return (
    <Badge className="bg-surface text-muted" title="Created by a partner webhook">
      <Webhook aria-hidden="true" className="size-3" />
      Partner
    </Badge>
  );
}

const SLA_CLASSES = {
  ok: 'text-sla-ok',
  risk: 'text-sla-risk',
  breached: 'text-sla-breached',
  stopped: 'text-muted',
} as const;

/** Live SLA countdown ("2h 14m left" / "Breached 35m ago"), computed from the due date. */
export function SlaIndicator({
  status,
  slaDueAt,
  slaStartedAt,
  slaBreached,
  tick = 1000,
  className,
}: {
  status: TicketStatus;
  slaDueAt: string;
  slaStartedAt?: string;
  slaBreached: boolean;
  tick?: number;
  className?: string;
}) {
  const now = useNow(tick);
  const state = slaState({
    status,
    slaDueAt,
    slaBreached,
    now,
    ...(slaStartedAt ? { slaStartedAt } : {}),
  });
  const seconds = Math.round((Date.parse(slaDueAt) - now) / 1000);
  const text =
    state === 'stopped'
      ? 'SLA stopped'
      : seconds >= 0
        ? `${formatDuration(seconds)} left`
        : `Breached ${formatDuration(-seconds)} ago`;
  const Icon = state === 'breached' ? AlertOctagon : Clock;
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 text-sm font-semibold tabular-nums',
        SLA_CLASSES[state],
        className,
      )}
    >
      <Icon aria-hidden="true" className="size-4" />
      <span>{text}</span>
      {state === 'risk' && <span className="sr-only">(at risk)</span>}
    </span>
  );
}
