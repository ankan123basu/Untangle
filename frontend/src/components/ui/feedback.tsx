import type { ReactNode } from 'react';
import { AlertTriangle, Inbox } from 'lucide-react';
import { ApiError, messageFor } from '@/lib/api/problem';
import { cn } from '@/lib/utils';
import { Button } from './button';

export function Skeleton({ className }: { className?: string }) {
  return (
    <div aria-hidden="true" className={cn('animate-pulse rounded-md bg-paper-2', className)} />
  );
}

export function EmptyState({
  title,
  description,
  action,
}: {
  title: string;
  description?: string;
  action?: ReactNode;
}) {
  return (
    <div className="flex flex-col items-center gap-3 px-6 py-12 text-center">
      <Inbox aria-hidden="true" className="size-10 text-muted" />
      <p className="font-display text-lg font-bold">{title}</p>
      {description && <p className="max-w-sm text-sm text-muted">{description}</p>}
      {action}
    </div>
  );
}

/**
 * Error panel. Pass `error` (anything thrown by the API client) and it shows the friendly message
 * and the server traceId so support can find the request in the logs.
 */
export function ErrorState({
  title = 'Something went wrong',
  description,
  onRetry,
  traceId,
  error,
}: {
  title?: string;
  description?: string | undefined;
  onRetry?: (() => void) | undefined;
  traceId?: string | undefined;
  error?: unknown;
}) {
  if (error instanceof ApiError) {
    description ??= messageFor(error);
    traceId ??= error.traceId;
  }
  return (
    <div role="alert" className="flex flex-col items-center gap-3 px-6 py-12 text-center">
      <AlertTriangle aria-hidden="true" className="size-10 text-danger" />
      <p className="font-display text-lg font-bold">{title}</p>
      {description && <p className="max-w-sm text-sm text-muted">{description}</p>}
      {traceId && (
        <p className="font-mono text-xs text-muted">
          Reference: <span className="select-all">{traceId}</span>
        </p>
      )}
      {onRetry && (
        <Button variant="secondary" onClick={onRetry}>
          Try again
        </Button>
      )}
    </div>
  );
}

export function Kbd({ children }: { children: ReactNode }) {
  return (
    <kbd className="rounded-sm border border-line bg-surface px-1.5 font-mono text-[11px] leading-5 font-bold shadow-soft-sm">
      {children}
    </kbd>
  );
}
