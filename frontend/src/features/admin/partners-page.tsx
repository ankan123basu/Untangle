import { useState } from 'react';
import { Link } from 'react-router';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { AlertTriangle, Building2, Copy, KeyRound } from 'lucide-react';
import { toast } from 'sonner';
import { z } from 'zod';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Dialog } from '@/components/ui/dialog';
import { EmptyState, ErrorState, Skeleton } from '@/components/ui/feedback';
import { Field, Input } from '@/components/ui/field';
import { api } from '@/lib/api/client';
import { ApiError, messageFor } from '@/lib/api/problem';
import type { IssuedSecret, PartnerView } from '@/lib/api/types';
import { queryKeys } from '@/lib/query-client';
import { formatDateTime } from '@/lib/utils';

function showError(error: unknown) {
  toast.error(error instanceof ApiError ? messageFor(error) : 'Something went wrong.');
}

const schema = z.object({
  code: z
    .string()
    .trim()
    .regex(
      /^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$/,
      'Lowercase letters, digits and dashes, 3–64 characters',
    ),
  name: z.string().trim().min(2, 'At least 2 characters').max(120),
  rateLimitPerMinute: z.coerce.number<string>().int().min(1).max(10000),
});
type FormInput = z.input<typeof schema>;
type FormOutput = z.output<typeof schema>;

/**
 * The signing secret is shown exactly once (§7.3). It is held only in this component's state and
 * disappears when the dialog closes; it never touches the query cache or storage.
 */
function SecretDialog({ issued, onClose }: { issued: IssuedSecret | null; onClose: () => void }) {
  const [copied, setCopied] = useState(false);
  const close = () => {
    setCopied(false);
    onClose();
  };
  return (
    <Dialog
      open={issued !== null}
      onClose={close}
      title={`Signing secret for ${issued?.partner.code ?? ''}`}
      description={issued?.notice}
      footer={
        <Button variant="primary" onClick={close}>
          I’ve stored it safely
        </Button>
      }
    >
      <div className="flex flex-col gap-3">
        <p className="flex items-start gap-2 rounded-md border-2 border-line bg-prio-high-bg p-3 text-sm text-prio-high-fg">
          <AlertTriangle aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
          This is the only time the secret is shown. Send it to the partner over a secure channel.
        </p>
        <div className="flex items-stretch gap-2">
          <code
            aria-label="Signing secret"
            className="min-w-0 flex-1 rounded-md border-2 border-line bg-paper-2 px-3 py-2 font-mono text-sm break-all select-all"
          >
            {issued?.secret}
          </code>
          <Button
            aria-label="Copy secret"
            icon={<Copy className="size-4" />}
            onClick={() => {
              if (!issued) return;
              void navigator.clipboard.writeText(issued.secret).then(
                () => {
                  setCopied(true);
                  toast.success('Copied');
                },
                () => toast.error('Copy failed. Select the text instead.'),
              );
            }}
          >
            {copied ? 'Copied' : 'Copy'}
          </Button>
        </div>
      </div>
    </Dialog>
  );
}

function CreatePartnerDialog({
  open,
  onClose,
  onCreated,
}: {
  open: boolean;
  onClose: () => void;
  onCreated: (issued: IssuedSecret) => void;
}) {
  const client = useQueryClient();
  const form = useForm<FormInput, unknown, FormOutput>({
    resolver: zodResolver(schema),
    defaultValues: { code: '', name: '', rateLimitPerMinute: '120' },
  });
  const { errors, isSubmitting } = form.formState;
  const close = () => {
    form.reset();
    onClose();
  };
  const submit = form.handleSubmit(async (values) => {
    try {
      const { data } = await api.post<IssuedSecret>('/admin/partners', values);
      void client.invalidateQueries({ queryKey: queryKeys.partners });
      close();
      onCreated(data);
    } catch (error) {
      if (error instanceof ApiError && error.code === 'PARTNER_CODE_TAKEN') {
        form.setError('code', { message: messageFor(error) });
      } else showError(error);
    }
  });
  return (
    <Dialog
      open={open}
      onClose={close}
      title="Add a partner"
      description="Partners send complaints to the HMAC-signed webhook."
      footer={
        <>
          <Button variant="ghost" onClick={close}>
            Cancel
          </Button>
          <Button variant="primary" loading={isSubmitting} onClick={() => void submit()}>
            Create and show secret
          </Button>
        </>
      }
    >
      <form className="flex flex-col gap-4" noValidate onSubmit={(e) => void submit(e)}>
        <Field
          label="Code"
          required
          error={errors.code?.message}
          hint="Sent by the partner in X-Partner-Code."
        >
          {(p) => (
            <Input
              {...p}
              placeholder="acme-bank"
              className="font-mono"
              {...form.register('code')}
            />
          )}
        </Field>
        <Field label="Display name" required error={errors.name?.message}>
          {(p) => <Input {...p} {...form.register('name')} />}
        </Field>
        <Field
          label="Rate limit (requests per minute)"
          required
          error={errors.rateLimitPerMinute?.message}
        >
          {(p) => (
            <Input
              {...p}
              type="number"
              min={1}
              max={10000}
              {...form.register('rateLimitPerMinute')}
            />
          )}
        </Field>
      </form>
    </Dialog>
  );
}

/** Screen 7 (§12.5): partners, their secrets, and a link to their delivery logs. */
export default function PartnersPage() {
  const client = useQueryClient();
  const [creating, setCreating] = useState(false);
  const [issued, setIssued] = useState<IssuedSecret | null>(null);
  const [rotating, setRotating] = useState<PartnerView | null>(null);
  const [loadedAt] = useState(Date.now);

  const partners = useQuery({
    queryKey: queryKeys.partners,
    queryFn: async () => (await api.get<PartnerView[]>('/admin/partners')).data,
  });
  const rotate = useMutation({
    mutationFn: async (p: PartnerView) =>
      (await api.post<IssuedSecret>(`/admin/partners/${p.id}/rotate-secret`)).data,
    onSuccess: (data) => {
      void client.invalidateQueries({ queryKey: queryKeys.partners });
      setRotating(null);
      setIssued(data);
    },
    onError: showError,
  });
  const setActive = useMutation({
    mutationFn: async (v: { partner: PartnerView; active: boolean }) =>
      (await api.patch<PartnerView>(`/admin/partners/${v.partner.id}/active`, { active: v.active }))
        .data,
    onSuccess: (p) => {
      void client.invalidateQueries({ queryKey: queryKeys.partners });
      toast.success(`${p.name} ${p.active ? 'enabled' : 'disabled'}`);
    },
    onError: showError,
  });

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="font-display text-3xl font-bold">Partners</h1>
          <p className="text-muted">Who may send tickets through the webhook.</p>
        </div>
        <Button
          variant="primary"
          icon={<Building2 className="size-4" />}
          onClick={() => {
            setCreating(true);
          }}
        >
          Add partner
        </Button>
      </div>
      <section className="solid-panel overflow-x-auto">
        {partners.isError ? (
          <ErrorState error={partners.error} onRetry={() => void partners.refetch()} />
        ) : !partners.data ? (
          <div className="flex flex-col gap-3 p-4">
            <Skeleton className="h-12" />
            <Skeleton className="h-12" />
          </div>
        ) : partners.data.length === 0 ? (
          <EmptyState
            title="No partners yet"
            description="Add one to start receiving webhook tickets."
          />
        ) : (
          <table className="stack-table w-full text-left text-sm md:min-w-[720px]">
            <caption className="sr-only">Partners</caption>
            <thead className="bg-paper-2 text-xs font-bold text-muted uppercase">
              <tr>
                <th scope="col" className="px-4 py-2">
                  Partner
                </th>
                <th scope="col" className="py-2 pr-3">
                  Status
                </th>
                <th scope="col" className="py-2 pr-3">
                  Rate limit
                </th>
                <th scope="col" className="py-2 pr-3">
                  Secret
                </th>
                <th scope="col" className="py-2 pr-4 text-right">
                  Actions
                </th>
              </tr>
            </thead>
            <tbody>
              {partners.data.map((p) => (
                <tr key={p.id} className="border-t-2 border-line-soft">
                  <th scope="row" className="px-4 py-3 font-semibold">
                    {p.name}
                    <span className="block font-mono text-xs font-normal text-muted">{p.code}</span>
                  </th>
                  <td data-label="Status" className="py-3 pr-3">
                    <Badge
                      className={
                        p.active
                          ? 'bg-status-resolved-bg text-status-resolved-fg'
                          : 'bg-status-closed-bg text-status-closed-fg'
                      }
                    >
                      {p.active ? 'Active' : 'Disabled'}
                    </Badge>
                  </td>
                  <td data-label="Rate limit" className="py-3 pr-3 tabular-nums">
                    {p.rateLimitPerMinute}/min
                  </td>
                  <td data-label="Secret" className="py-3 pr-3 text-xs text-muted">
                    {p.previousSecretValidUntil && Date.parse(p.previousSecretValidUntil) > loadedAt
                      ? `Old secret valid until ${formatDateTime(p.previousSecretValidUntil)}`
                      : 'One secret active'}
                  </td>
                  <td className="py-3 pr-4">
                    <div className="flex flex-wrap gap-2 md:justify-end">
                      <Link
                        to={`/admin/webhooks?partnerId=${p.id}`}
                        className="inline-flex h-8 items-center rounded-md px-3 text-sm font-semibold hover:bg-paper-2"
                      >
                        Logs
                      </Link>
                      <Button
                        size="sm"
                        icon={<KeyRound className="size-4" />}
                        onClick={() => {
                          setRotating(p);
                        }}
                      >
                        Rotate secret
                      </Button>
                      <Button
                        size="sm"
                        variant="ghost"
                        onClick={() => {
                          setActive.mutate({ partner: p, active: !p.active });
                        }}
                      >
                        {p.active ? 'Disable' : 'Enable'}
                      </Button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      <CreatePartnerDialog
        open={creating}
        onClose={() => {
          setCreating(false);
        }}
        onCreated={setIssued}
      />
      <Dialog
        open={rotating !== null}
        onClose={() => {
          setRotating(null);
        }}
        title={`Rotate ${rotating?.code ?? ''}’s secret?`}
        description="The old secret keeps working for 24 hours so the partner can switch without downtime."
        footer={
          <>
            <Button
              variant="ghost"
              onClick={() => {
                setRotating(null);
              }}
            >
              Cancel
            </Button>
            <Button
              variant="primary"
              loading={rotate.isPending}
              onClick={() => {
                if (rotating) rotate.mutate(rotating);
              }}
            >
              Rotate
            </Button>
          </>
        }
      >
        <p className="text-sm">A new secret will be shown once.</p>
      </Dialog>
      <SecretDialog
        issued={issued}
        onClose={() => {
          setIssued(null);
        }}
      />
    </div>
  );
}
