import { useState } from 'react';
import { useNavigate } from 'react-router';
import { useForm, useWatch } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Check, Sparkles, X } from 'lucide-react';
import { toast } from 'sonner';
import { z } from 'zod';
import { Button } from '@/components/ui/button';
import { Card, CardBody } from '@/components/ui/card';
import { Field, Input, Select, Textarea } from '@/components/ui/field';
import { api } from '@/lib/api/client';
import { ApiError, messageFor } from '@/lib/api/problem';
import {
  CATEGORIES,
  PRIORITIES,
  type CreateTicket,
  type TicketDetail,
  type TriageSuggestion,
} from '@/lib/api/types';
import { queryKeys } from '@/lib/query-client';
import { humanize } from '@/lib/utils';
import { useCan, useSession } from '@/features/auth/session-store';
import { useAgentOptions, useAiStatus, useWorkload } from './ticket-api';

/** Mirrors the backend's Bean Validation rules (§12.6: the only rules the UI duplicates). */
const schema = z.object({
  subject: z.string().trim().min(5, 'At least 5 characters').max(150, 'At most 150 characters'),
  description: z
    .string()
    .trim()
    .min(10, 'Describe the problem in at least 10 characters')
    .max(5000, 'At most 5000 characters'),
  customerName: z
    .string()
    .trim()
    .min(2, 'At least 2 characters')
    .max(120, 'At most 120 characters'),
  customerEmail: z.union([z.literal(''), z.email('Enter a valid email').max(254)]),
  category: z.enum(CATEGORIES, { error: 'Pick a category' }),
  priority: z.enum(PRIORITIES, { error: 'Pick a priority' }),
  assigneeId: z.string(),
});
type FormValues = z.infer<typeof schema>;

function feedback(insightId: string, accepted: boolean) {
  void api.patch(`/ai/insights/${insightId}/feedback`, { accepted }).catch(() => undefined);
}

/** Screen 4 (§12.5): fast, correct ticket intake, with optional AI triage suggestions. */
export default function CreateTicketPage() {
  const navigate = useNavigate();
  const client = useQueryClient();
  const me = useSession((s) => s.agent);
  const canAssignAny = useCan('TICKET_ASSIGN_ANY');
  const canSeeWorkload = useCan('AGENT_WORKLOAD_VIEW');
  const canUseAi = useCan('AI_USE');
  const ai = useAiStatus();
  const agents = useAgentOptions();
  const workload = useWorkload(canAssignAny && canSeeWorkload);
  const openCount = new Map(workload.data?.map((w) => [w.agentId, w.open + w.inProgress]));
  const [suggestion, setSuggestion] = useState<TriageSuggestion | null>(null);

  const form = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      subject: '',
      description: '',
      customerName: '',
      customerEmail: '',
      category: 'GENERAL',
      priority: 'MEDIUM',
      assigneeId: '',
    },
  });
  const { errors, isSubmitting } = form.formState;
  const [subject, description, assigneeId] = useWatch({
    control: form.control,
    name: ['subject', 'description', 'assigneeId'],
  });
  const watched = { subject, description, assigneeId };

  const triage = useMutation({
    mutationFn: async () =>
      (
        await api.post<TriageSuggestion>('/ai/triage', {
          subject: form.getValues('subject'),
          description: form.getValues('description'),
        })
      ).data,
    onSuccess: setSuggestion,
    onError: (e) => {
      toast.error(e instanceof ApiError ? messageFor(e) : 'AI is unavailable right now.');
    },
  });

  const submit = form.handleSubmit(async (values) => {
    const body: CreateTicket = {
      subject: values.subject,
      description: values.description,
      customerName: values.customerName,
      category: values.category,
      priority: values.priority,
      ...(values.customerEmail ? { customerEmail: values.customerEmail } : {}),
      ...(values.assigneeId ? { assigneeId: values.assigneeId } : {}),
    };
    try {
      const created = (await api.post<TicketDetail>('/tickets', body)).data;
      void client.invalidateQueries({ queryKey: queryKeys.tickets.all });
      void client.invalidateQueries({ queryKey: queryKeys.dashboard.all });
      toast.success(`${created.ticketNumber} created`);
      void navigate(`/tickets/${created.id}`);
    } catch (error) {
      if (error instanceof ApiError && error.fieldErrors.length > 0) {
        // Show the server's field errors inline, next to the right field.
        for (const fe of error.fieldErrors) {
          if (fe.field in values)
            form.setError(fe.field as keyof FormValues, { message: fe.message });
        }
      }
      toast.error(error instanceof ApiError ? messageFor(error) : 'Could not create the ticket.');
    }
  });

  const aiReady = canUseAi && ai.data?.enabled;
  const canSuggest = watched.subject.trim().length >= 5 && watched.description.trim().length >= 10;

  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-4">
      <div>
        <h1 className="font-display text-3xl font-bold">New ticket</h1>
        <p className="text-muted">Log a complaint that came in by phone, email or chat.</p>
      </div>
      <Card>
        <CardBody>
          <form className="flex flex-col gap-5" onSubmit={(e) => void submit(e)} noValidate>
            <Field label="Subject" required error={errors.subject?.message}>
              {(props) => (
                <Input
                  {...props}
                  placeholder="Refund not received for order 4471"
                  {...form.register('subject')}
                />
              )}
            </Field>
            <Field label="Description" required error={errors.description?.message}>
              {(props) => (
                <Textarea
                  {...props}
                  rows={6}
                  placeholder="What happened, when, and what the customer expects."
                  {...form.register('description')}
                />
              )}
            </Field>

            {aiReady && (
              <section
                aria-label="AI triage"
                className="flex flex-col gap-3 rounded-md border-2 border-dashed border-line-soft p-3"
              >
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <p className="text-sm text-muted">
                    Let AI suggest a category and priority. You decide.
                  </p>
                  <Button
                    size="sm"
                    icon={<Sparkles className="size-4" />}
                    loading={triage.isPending}
                    disabled={!canSuggest}
                    onClick={() => {
                      triage.mutate();
                    }}
                  >
                    Suggest with AI
                  </Button>
                </div>
                {suggestion && (
                  <div className="flex flex-col gap-2" aria-live="polite">
                    {suggestion.summary && <p className="text-sm">{suggestion.summary}</p>}
                    <div className="flex flex-wrap gap-2">
                      {suggestion.category && (
                        <SuggestionChip
                          label={`Category: ${humanize(suggestion.category)}`}
                          onAccept={() => {
                            if (suggestion.category)
                              form.setValue('category', suggestion.category, { shouldDirty: true });
                            feedback(suggestion.insightId, true);
                            setSuggestion({ ...suggestion, category: null });
                          }}
                          onIgnore={() => {
                            feedback(suggestion.insightId, false);
                            setSuggestion({ ...suggestion, category: null });
                          }}
                        />
                      )}
                      {suggestion.priority && (
                        <SuggestionChip
                          label={`Priority: ${humanize(suggestion.priority)}`}
                          onAccept={() => {
                            if (suggestion.priority)
                              form.setValue('priority', suggestion.priority, { shouldDirty: true });
                            feedback(suggestion.insightId, true);
                            setSuggestion({ ...suggestion, priority: null });
                          }}
                          onIgnore={() => {
                            feedback(suggestion.insightId, false);
                            setSuggestion({ ...suggestion, priority: null });
                          }}
                        />
                      )}
                    </div>
                    {suggestion.rationale && (
                      <p className="text-xs text-muted">Why: {suggestion.rationale}</p>
                    )}
                  </div>
                )}
              </section>
            )}

            <div className="grid gap-4 sm:grid-cols-2">
              <Field label="Category" required error={errors.category?.message}>
                {(props) => (
                  <Select {...props} {...form.register('category')}>
                    {CATEGORIES.map((c) => (
                      <option key={c} value={c}>
                        {humanize(c)}
                      </option>
                    ))}
                  </Select>
                )}
              </Field>
              <Field
                label="Priority"
                required
                error={errors.priority?.message}
                hint="Sets the SLA deadline."
              >
                {(props) => (
                  <Select {...props} {...form.register('priority')}>
                    {PRIORITIES.map((p) => (
                      <option key={p} value={p}>
                        {humanize(p)}
                      </option>
                    ))}
                  </Select>
                )}
              </Field>
              <Field label="Customer name" required error={errors.customerName?.message}>
                {(props) => (
                  <Input {...props} autoComplete="off" {...form.register('customerName')} />
                )}
              </Field>
              <Field label="Customer email" error={errors.customerEmail?.message} hint="Optional.">
                {(props) => (
                  <Input
                    {...props}
                    type="email"
                    autoComplete="off"
                    {...form.register('customerEmail')}
                  />
                )}
              </Field>
            </div>

            {canAssignAny ? (
              <Field
                label="Assign to"
                hint={canSeeWorkload ? 'Counts show each agent’s open tickets.' : undefined}
              >
                {(props) => (
                  <Select {...props} {...form.register('assigneeId')}>
                    <option value="">Leave unassigned (queue)</option>
                    {agents.data?.map((a) => {
                      const n = openCount.get(a.id);
                      return (
                        <option key={a.id} value={a.id}>
                          {a.fullName} · {humanize(a.role)}
                          {n !== undefined ? ` · ${String(n)} open` : ''}
                        </option>
                      );
                    })}
                  </Select>
                )}
              </Field>
            ) : (
              me && (
                <label className="flex items-center gap-2 text-sm font-semibold">
                  <input
                    type="checkbox"
                    className="size-4 accent-[var(--ut-accent)]"
                    checked={watched.assigneeId === me.id}
                    onChange={(e) => {
                      form.setValue('assigneeId', e.target.checked ? me.id : '');
                    }}
                  />
                  Assign to me
                </label>
              )
            )}

            <div className="flex justify-end gap-2 border-t-2 border-line-soft pt-4">
              <Button
                variant="ghost"
                onClick={() => {
                  void navigate(-1);
                }}
              >
                Cancel
              </Button>
              <Button type="submit" variant="primary" loading={isSubmitting}>
                Create ticket
              </Button>
            </div>
          </form>
        </CardBody>
      </Card>
    </div>
  );
}

function SuggestionChip({
  label,
  onAccept,
  onIgnore,
}: {
  label: string;
  onAccept: () => void;
  onIgnore: () => void;
}) {
  return (
    <span className="inline-flex items-center gap-1 rounded-md border-2 border-line bg-brand/30 py-0.5 pr-0.5 pl-2 text-sm font-semibold">
      {label}
      <Button
        variant="ghost"
        size="sm"
        className="h-7 px-1.5"
        aria-label={`Accept ${label}`}
        icon={<Check className="size-4" />}
        onClick={onAccept}
      />
      <Button
        variant="ghost"
        size="sm"
        className="h-7 px-1.5"
        aria-label={`Ignore ${label}`}
        icon={<X className="size-4" />}
        onClick={onIgnore}
      />
    </span>
  );
}
