import { useState } from 'react';
import { Send } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Kbd } from '@/components/ui/feedback';
import { Textarea } from '@/components/ui/field';
import { Tooltip } from '@/components/ui/tooltip';
import { ApiError, messageFor } from '@/lib/api/problem';
import { useAddComment } from '../ticket-api';

const MAX = 5000;

/**
 * Plain-text comment box (no Markdown). Ctrl/⌘+Enter sends. The draft is controlled by the page
 * so the AI panel can drop a reply draft into it.
 */
export function Composer({
  ticketId,
  value,
  onChange,
  disabledReason,
}: {
  ticketId: string;
  value: string;
  onChange: (text: string) => void;
  disabledReason: string | undefined;
}) {
  const add = useAddComment(ticketId);
  const [error, setError] = useState<string>();
  const disabled = disabledReason !== undefined;

  const send = () => {
    const body = value.trim();
    if (!body || disabled) return;
    onChange('');
    setError(undefined);
    add.mutate(
      { body, type: 'COMMENT' },
      {
        onError: (e) => {
          onChange(body); // give the text back so nothing is lost
          const message = e instanceof ApiError ? messageFor(e) : 'Could not add the comment.';
          setError(message);
          toast.error(message);
        },
      },
    );
  };

  return (
    <form
      aria-label="Add a comment"
      className="flex flex-col gap-2"
      onSubmit={(e) => {
        e.preventDefault();
        send();
      }}
    >
      <label htmlFor="composer" className="sr-only">
        Comment
      </label>
      <Textarea
        id="composer"
        rows={3}
        maxLength={MAX}
        disabled={disabled}
        placeholder={disabled ? disabledReason : 'Write an update for the team…'}
        value={value}
        aria-invalid={Boolean(error)}
        aria-describedby="composer-help"
        onChange={(e) => {
          onChange(e.target.value);
        }}
        onKeyDown={(e) => {
          if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
            e.preventDefault();
            send();
          }
        }}
      />
      <div className="flex items-center justify-between gap-3">
        <p id="composer-help" className="text-xs text-muted">
          {error ?? (
            <>
              Plain text. <Kbd>Ctrl</Kbd> + <Kbd>Enter</Kbd> to send · {value.length}/{MAX}
            </>
          )}
        </p>
        <Tooltip content={disabledReason}>
          <Button
            type="submit"
            variant="primary"
            size="sm"
            disabled={disabled || value.trim().length === 0}
            icon={<Send className="size-4" />}
          >
            Comment
          </Button>
        </Tooltip>
      </div>
    </form>
  );
}
