import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { Dialog } from '@/components/ui/dialog';
import { Field, Textarea } from '@/components/ui/field';

export interface ActionRequest {
  title: string;
  description: string;
  /** What the text box collects. */
  input: 'note' | 'reason';
  confirmLabel: string;
  tone?: 'primary' | 'danger';
  onConfirm: (text: string) => Promise<unknown>;
}

const LIMITS = { note: { min: 1, max: 5000 }, reason: { min: 3, max: 500 } } as const;

/**
 * Collects the resolution note or reason an action needs, then runs it. Stays open (with the
 * error toast from the caller) if the server rejects it, so the typed text isn't lost.
 */
export function ActionDialog({
  request,
  onClose,
}: {
  request: ActionRequest | null;
  onClose: () => void;
}) {
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const limit = request ? LIMITS[request.input] : LIMITS.reason;

  const close = () => {
    setText('');
    setError(undefined);
    onClose();
  };

  const submit = async () => {
    if (!request) return;
    const value = text.trim();
    if (value.length < limit.min) {
      setError(request.input === 'note' ? 'Write a resolution note' : 'Give a short reason');
      return;
    }
    setBusy(true);
    try {
      await request.onConfirm(value);
      close();
    } catch {
      // The caller already showed the error; keep the dialog and the text.
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog
      open={request !== null}
      onClose={close}
      title={request?.title ?? ''}
      description={request?.description}
      footer={
        <>
          <Button variant="ghost" onClick={close}>
            Cancel
          </Button>
          <Button
            variant={request?.tone === 'danger' ? 'danger' : 'primary'}
            loading={busy}
            onClick={() => void submit()}
          >
            {request?.confirmLabel}
          </Button>
        </>
      }
    >
      <form
        onSubmit={(e) => {
          e.preventDefault();
          void submit();
        }}
      >
        <Field
          label={request?.input === 'note' ? 'Resolution note' : 'Reason'}
          required
          error={error}
          hint={request?.input === 'note' ? 'Customers and auditors see what fixed it.' : undefined}
        >
          {(props) => (
            <Textarea
              {...props}
              data-autofocus
              rows={request?.input === 'note' ? 5 : 3}
              maxLength={limit.max}
              value={text}
              onChange={(e) => {
                setText(e.target.value);
                setError(undefined);
              }}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
                  e.preventDefault();
                  void submit();
                }
              }}
            />
          )}
        </Field>
      </form>
    </Dialog>
  );
}
