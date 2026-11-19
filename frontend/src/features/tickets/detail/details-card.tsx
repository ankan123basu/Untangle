import type { ReactNode } from 'react';
import { UserPlus } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardBody, CardHeader, CardTitle } from '@/components/ui/card';
import { Select } from '@/components/ui/field';
import { Tooltip } from '@/components/ui/tooltip';
import type { AgentOption, TicketDetail } from '@/lib/api/types';
import { useCan, useSession } from '@/features/auth/session-store';
import { formatDateTime, humanize } from '@/lib/utils';
import { deniedReason } from '../ticket-meta';
import { useAgentOptions, useWorkload } from '../ticket-api';
import { BotBadge } from '@/components/ui/bot-badge';
import { useAiTeammate } from '@/features/ai/ai-teammate';

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="grid grid-cols-[7.5rem_1fr] gap-2 py-1.5">
      <dt className="text-muted">{label}</dt>
      <dd className="min-w-0 font-medium break-words">{children}</dd>
    </div>
  );
}

/**
 * Facts about the ticket plus the assignee picker. Leads/admins pick anyone (with workload counts
 * so they can balance the queue); agents get "Assign to me".
 */
export function DetailsCard({
  ticket,
  onAssign,
  assigning,
}: {
  ticket: TicketDetail;
  onAssign: (agent: AgentOption | null) => void;
  assigning: boolean;
}) {
  const me = useSession((s) => s.agent);
  const canAssign = ticket.allowedActions.includes('ASSIGN');
  const canSelfAssign = ticket.allowedActions.includes('SELF_ASSIGN');
  const agents = useAgentOptions();
  const teammate = useAiTeammate();
  const workload = useWorkload(useCan('AGENT_WORKLOAD_VIEW') && canAssign);
  const openCount = new Map(workload.data?.map((w) => [w.agentId, w.open + w.inProgress]));
  const assignDenied = deniedReason(ticket.deniedActions.find((d) => d.action === 'ASSIGN'));

  return (
    <Card>
      <CardHeader>
        <CardTitle>Details</CardTitle>
      </CardHeader>
      <CardBody>
        <div className="mb-3 flex flex-col gap-2">
          <label htmlFor="assignee" className="text-sm font-semibold">
            Assignee
          </label>
          {canAssign ? (
            <Select
              id="assignee"
              disabled={assigning}
              value={ticket.assignee?.id ?? ''}
              onChange={(e) => {
                const agent = agents.data?.find((a) => a.id === e.target.value) ?? null;
                onAssign(agent);
              }}
            >
              <option value="">Unassigned</option>
              {/* Keep the current assignee selectable even if inactive and missing from options. */}
              {ticket.assignee && !agents.data?.some((a) => a.id === ticket.assignee?.id) && (
                <option value={ticket.assignee.id}>{ticket.assignee.fullName}</option>
              )}
              {agents.data?.map((a) => {
                const count = openCount.get(a.id);
                // The AI teammate (ADR-028): only when AI is on; it works, a lead approves.
                const aiOff = a.ai && !teammate.data?.enabled;
                return (
                  <option key={a.id} value={a.id} disabled={aiOff}>
                    {a.ai
                      ? `${a.fullName} · AI teammate${aiOff ? ' (switched off)' : ''}`
                      : `${a.fullName} · ${humanize(a.role)}`}
                    {count !== undefined && !a.ai ? ` · ${String(count)} open` : ''}
                  </option>
                );
              })}
            </Select>
          ) : (
            <Tooltip content={canSelfAssign ? undefined : assignDenied}>
              <p
                id="assignee"
                className="flex items-center justify-between gap-2 font-medium"
                tabIndex={canSelfAssign ? -1 : 0}
              >
                {ticket.assignee ? (
                  <span>
                    {ticket.assignee.fullName} {ticket.assignee.ai && <BotBadge />}
                  </span>
                ) : (
                  <span className="text-muted italic">Unassigned</span>
                )}
              </p>
            </Tooltip>
          )}
          {canSelfAssign && ticket.assignee?.id !== me?.id && (
            <Button
              size="sm"
              loading={assigning}
              icon={<UserPlus className="size-4" />}
              onClick={() => {
                if (me) onAssign({ id: me.id, fullName: me.fullName, role: me.role, ai: false });
              }}
            >
              Assign to me
            </Button>
          )}
        </div>
        <dl className="border-t-2 border-line-soft pt-2 text-sm">
          <Row label="Customer">
            {ticket.customerName}
            {ticket.customerEmail && (
              <span className="block text-muted">{ticket.customerEmail}</span>
            )}
          </Row>
          <Row label="Category">{humanize(ticket.category)}</Row>
          <Row label="Source">
            {ticket.source === 'WEBHOOK'
              ? `Partner ${ticket.partnerCode ?? ''}`
              : 'Created by an agent'}
            {ticket.externalEventId && (
              <span className="block font-mono text-xs text-muted">{ticket.externalEventId}</span>
            )}
          </Row>
          {ticket.createdBy && <Row label="Created by">{ticket.createdBy.fullName}</Row>}
          <Row label="Created">{formatDateTime(ticket.createdAt)}</Row>
          <Row label="SLA due">{formatDateTime(ticket.slaDueAt)}</Row>
          {ticket.firstResponseAt && (
            <Row label="First response">{formatDateTime(ticket.firstResponseAt)}</Row>
          )}
          {ticket.resolvedAt && <Row label="Resolved">{formatDateTime(ticket.resolvedAt)}</Row>}
          {ticket.closedAt && <Row label="Closed">{formatDateTime(ticket.closedAt)}</Row>}
          {ticket.reopenedCount > 0 && <Row label="Reopened">{ticket.reopenedCount}×</Row>}
        </dl>
      </CardBody>
    </Card>
  );
}
