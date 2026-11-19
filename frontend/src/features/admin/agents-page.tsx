import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Lock, UserPlus } from 'lucide-react';
import { toast } from 'sonner';
import { z } from 'zod';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Dialog } from '@/components/ui/dialog';
import { EmptyState, ErrorState, Skeleton } from '@/components/ui/feedback';
import { Field, Input, Select } from '@/components/ui/field';
import { api } from '@/lib/api/client';
import { ApiError, messageFor } from '@/lib/api/problem';
import type { AgentAdminView, BulkAssignResult, Role, TicketPage } from '@/lib/api/types';
import { queryKeys } from '@/lib/query-client';
import { formatDateTime, humanize, timeAgo } from '@/lib/utils';
import { useSession } from '@/features/auth/session-store';
import { BotBadge } from '@/components/ui/bot-badge';

const ROLES: Role[] = ['AGENT', 'LEAD', 'ADMIN'];

function showError(error: unknown) {
  toast.error(error instanceof ApiError ? messageFor(error) : 'Something went wrong.');
}

const createSchema = z.object({
  fullName: z.string().trim().min(2, 'At least 2 characters').max(120),
  email: z.string().trim().pipe(z.email('Enter a valid email').max(254)),
  password: z.string().min(10, 'At least 10 characters').max(72, 'At most 72 characters'),
  role: z.enum(['AGENT', 'LEAD', 'ADMIN']),
});
type CreateValues = z.infer<typeof createSchema>;

function CreateAgentDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const client = useQueryClient();
  const form = useForm<CreateValues>({
    resolver: zodResolver(createSchema),
    defaultValues: { fullName: '', email: '', password: '', role: 'AGENT' },
  });
  const { errors, isSubmitting } = form.formState;
  const close = () => {
    form.reset();
    onClose();
  };
  const submit = form.handleSubmit(async (values) => {
    try {
      await api.post<AgentAdminView>('/agents', values);
      void client.invalidateQueries({ queryKey: ['agents'] });
      toast.success(`${values.fullName} can now sign in`);
      close();
    } catch (error) {
      if (error instanceof ApiError && error.code === 'EMAIL_TAKEN') {
        form.setError('email', { message: messageFor(error) });
      } else showError(error);
    }
  });
  return (
    <Dialog
      open={open}
      onClose={close}
      title="Add an agent"
      description="Share the temporary password with them privately."
      footer={
        <>
          <Button variant="ghost" onClick={close}>
            Cancel
          </Button>
          <Button variant="primary" loading={isSubmitting} onClick={() => void submit()}>
            Add agent
          </Button>
        </>
      }
    >
      <form className="flex flex-col gap-4" noValidate onSubmit={(e) => void submit(e)}>
        <Field label="Full name" required error={errors.fullName?.message}>
          {(p) => <Input {...p} autoComplete="off" {...form.register('fullName')} />}
        </Field>
        <Field label="Work email" required error={errors.email?.message}>
          {(p) => <Input {...p} type="email" autoComplete="off" {...form.register('email')} />}
        </Field>
        <Field
          label="Temporary password"
          required
          error={errors.password?.message}
          hint="10–72 characters."
        >
          {(p) => (
            <Input
              {...p}
              type="password"
              autoComplete="new-password"
              {...form.register('password')}
            />
          )}
        </Field>
        <Field label="Role" required>
          {(p) => (
            <Select {...p} {...form.register('role')}>
              {ROLES.map((r) => (
                <option key={r} value={r}>
                  {humanize(r)}
                </option>
              ))}
            </Select>
          )}
        </Field>
      </form>
    </Dialog>
  );
}

/**
 * Deactivating someone who still owns open tickets: move those tickets to a colleague first
 * (bulk assign), then deactivate.
 */
function ReassignDialog({
  agent,
  others,
  onClose,
}: {
  agent: AgentAdminView | null;
  others: AgentAdminView[];
  onClose: () => void;
}) {
  const client = useQueryClient();
  const [target, setTarget] = useState('');
  const run = useMutation({
    mutationFn: async () => {
      if (!agent) return { assigned: 0, skipped: 0 };
      const ids: string[] = [];
      for (let page = 0; ; page++) {
        const { data } = await api.get<TicketPage>('/tickets', {
          params: { assigneeId: agent.id, status: ['OPEN', 'IN_PROGRESS'], size: 100, page },
          paramsSerializer: { indexes: null },
        });
        ids.push(...data.content.map((t) => t.id));
        if (page + 1 >= data.totalPages) break;
      }
      let assigned = 0;
      let skipped = 0;
      for (let i = 0; i < ids.length; i += 200) {
        const { data } = await api.post<BulkAssignResult>('/tickets/bulk/assign', {
          ticketIds: ids.slice(i, i + 200),
          agentId: target,
        });
        assigned += data.assigned.length;
        skipped += data.skipped.length;
      }
      if (skipped === 0) await api.patch(`/agents/${agent.id}/active`, { active: false });
      return { assigned, skipped };
    },
    onSuccess: ({ assigned, skipped }) => {
      void client.invalidateQueries({ queryKey: ['agents'] });
      void client.invalidateQueries({ queryKey: ['tickets'] });
      void client.invalidateQueries({ queryKey: queryKeys.dashboard.all });
      if (skipped > 0)
        toast.warning(
          `${String(assigned)} moved, ${String(skipped)} could not be moved. Agent is still active.`,
        );
      else
        toast.success(
          `${String(assigned)} tickets moved and ${agent?.fullName ?? 'agent'} deactivated`,
        );
      setTarget('');
      onClose();
    },
    onError: showError,
  });
  return (
    <Dialog
      open={agent !== null}
      onClose={onClose}
      title={`Reassign ${agent?.fullName ?? ''}’s tickets`}
      description="They still own open tickets. Move them to a colleague, then they’ll be deactivated."
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button
            variant="danger"
            disabled={!target}
            loading={run.isPending}
            onClick={() => {
              run.mutate();
            }}
          >
            Move tickets and deactivate
          </Button>
        </>
      }
    >
      <Field label="Move open tickets to" required>
        {(p) => (
          <Select
            {...p}
            value={target}
            onChange={(e) => {
              setTarget(e.target.value);
            }}
          >
            <option value="">Choose an agent…</option>
            {others.map((a) => (
              <option key={a.id} value={a.id}>
                {a.fullName} · {humanize(a.role)}
              </option>
            ))}
          </Select>
        )}
      </Field>
    </Dialog>
  );
}

/** Screen 6 (§12.5): team management. */
export default function AgentsPage() {
  const me = useSession((s) => s.agent);
  const client = useQueryClient();
  const [creating, setCreating] = useState(false);
  const [reassigning, setReassigning] = useState<AgentAdminView | null>(null);

  const agents = useQuery({
    queryKey: queryKeys.agents.admin,
    queryFn: async () => (await api.get<AgentAdminView[]>('/admin/agents')).data,
  });
  const refresh = () => client.invalidateQueries({ queryKey: ['agents'] });

  const changeRole = useMutation({
    mutationFn: async (v: { agent: AgentAdminView; role: Role }) =>
      (await api.patch<AgentAdminView>(`/agents/${v.agent.id}`, { role: v.role })).data,
    onSuccess: (a) => {
      void refresh();
      toast.success(`${a.fullName} is now ${humanize(a.role).toLowerCase()}`);
    },
    onError: showError,
  });
  const setActive = useMutation({
    mutationFn: async (v: { agent: AgentAdminView; active: boolean }) =>
      (await api.patch<AgentAdminView>(`/agents/${v.agent.id}/active`, { active: v.active })).data,
    onSuccess: (a) => {
      void refresh();
      toast.success(`${a.fullName} ${a.active ? 'reactivated' : 'deactivated'}`);
    },
    onError: (error, v) => {
      if (error instanceof ApiError && error.code === 'AGENT_HAS_OPEN_TICKETS')
        setReassigning(v.agent);
      else showError(error);
    },
  });

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="font-display text-4xl font-bold tracking-tight">
            <span className="highlight">Agents</span>
          </h1>
          <p className="text-muted">Who can sign in, and what they can do.</p>
        </div>
        <Button
          variant="primary"
          icon={<UserPlus className="size-4" />}
          onClick={() => {
            setCreating(true);
          }}
        >
          Add agent
        </Button>
      </div>
      <section className="solid-panel overflow-x-auto">
        {agents.isError ? (
          <ErrorState error={agents.error} onRetry={() => void agents.refetch()} />
        ) : !agents.data ? (
          <div className="flex flex-col gap-3 p-4">
            <Skeleton className="h-12" />
            <Skeleton className="h-12" />
            <Skeleton className="h-12" />
          </div>
        ) : agents.data.length === 0 ? (
          <EmptyState title="No agents yet" />
        ) : (
          <table className="stack-table w-full text-left text-sm md:min-w-[720px]">
            <caption className="sr-only">Agents</caption>
            <thead className="bg-paper-2 text-xs font-bold text-muted uppercase">
              <tr>
                <th scope="col" className="px-4 py-2">
                  Agent
                </th>
                <th scope="col" className="py-2 pr-3">
                  Role
                </th>
                <th scope="col" className="py-2 pr-3">
                  Status
                </th>
                <th scope="col" className="py-2 pr-3">
                  Last sign-in
                </th>
                <th scope="col" className="py-2 pr-4 text-right">
                  Actions
                </th>
              </tr>
            </thead>
            <tbody>
              {agents.data.map((a) => {
                const self = a.id === me?.id;
                return (
                  <tr key={a.id} className="border-t-2 border-line-soft">
                    <th scope="row" className="px-4 py-3 font-semibold">
                      {a.fullName}{' '}
                      {self && <span className="text-xs font-normal text-muted">(you)</span>}
                      {a.ai && <BotBadge />}
                      <span className="block text-xs font-normal text-muted">
                        {a.ai ? 'AI teammate · cannot sign in · deactivate to switch off' : a.email}
                      </span>
                    </th>
                    <td data-label="Role" className="py-3 pr-3">
                      <Select
                        aria-label={`Role of ${a.fullName}`}
                        className="h-8 w-auto py-0"
                        value={a.role}
                        disabled={changeRole.isPending || a.ai}
                        title={a.ai ? 'The AI teammate is always an agent' : undefined}
                        onChange={(e) => {
                          changeRole.mutate({ agent: a, role: e.target.value as Role });
                        }}
                      >
                        {ROLES.map((r) => (
                          <option key={r} value={r}>
                            {humanize(r)}
                          </option>
                        ))}
                      </Select>
                    </td>
                    <td data-label="Status" className="py-3 pr-3">
                      <div className="flex flex-wrap gap-1">
                        <Badge
                          className={
                            a.active
                              ? 'bg-status-resolved-bg text-status-resolved-fg'
                              : 'bg-status-closed-bg text-status-closed-fg'
                          }
                        >
                          {a.active ? 'Active' : 'Inactive'}
                        </Badge>
                        {a.locked && (
                          <Badge
                            className="bg-prio-high-bg text-prio-high-fg"
                            title="Too many failed sign-ins; unlocks automatically"
                          >
                            <Lock aria-hidden="true" className="size-3" /> Locked
                          </Badge>
                        )}
                      </div>
                    </td>
                    <td data-label="Last sign-in" className="py-3 pr-3 text-muted">
                      {a.lastLoginAt ? (
                        <time dateTime={a.lastLoginAt} title={formatDateTime(a.lastLoginAt)}>
                          {timeAgo(a.lastLoginAt)}
                        </time>
                      ) : (
                        'Never'
                      )}
                    </td>
                    <td className="py-3 pr-4 text-right">
                      {!self && (
                        <Button
                          size="sm"
                          variant={a.active ? 'ghost' : 'secondary'}
                          loading={setActive.isPending && setActive.variables.agent.id === a.id}
                          onClick={() => {
                            setActive.mutate({ agent: a, active: !a.active });
                          }}
                        >
                          {a.active ? 'Deactivate' : 'Reactivate'}
                        </Button>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </section>
      <CreateAgentDialog
        open={creating}
        onClose={() => {
          setCreating(false);
        }}
      />
      <ReassignDialog
        agent={reassigning}
        others={(agents.data ?? []).filter((a) => a.active && a.id !== reassigning?.id)}
        onClose={() => {
          setReassigning(null);
        }}
      />
    </div>
  );
}
