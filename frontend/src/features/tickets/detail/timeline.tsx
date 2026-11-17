import { CheckCircle2, Cog, MessageSquare } from 'lucide-react';
import { Skeleton, ErrorState } from '@/components/ui/feedback';
import type { TimelineEntry } from '@/lib/api/types';
import { cn, formatDateTime, timeAgo } from '@/lib/utils';
import { useTimeline } from '../ticket-api';

function initials(name: string): string {
  return name
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => part.charAt(0).toUpperCase())
    .join('');
}

function EntryItem({ entry }: { entry: TimelineEntry }) {
  const pending = entry.id.startsWith('pending-');
  if (entry.type === 'SYSTEM') {
    return (
      <li className="flex items-start gap-3 pl-1 text-sm text-muted">
        <Cog aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
        <p className="min-w-0">
          {/* Plain text only: React escapes it, so stored HTML can never run (§11). */}
          <span className="break-words">{entry.body}</span>
          {entry.author && <span> · {entry.author.fullName}</span>}
          {' · '}
          <time dateTime={entry.createdAt} title={formatDateTime(entry.createdAt)}>
            {timeAgo(entry.createdAt)}
          </time>
        </p>
      </li>
    );
  }
  const note = entry.type === 'RESOLUTION_NOTE';
  return (
    <li className={cn('flex gap-3', pending && 'opacity-60')}>
      <span
        aria-hidden="true"
        className="flex size-9 shrink-0 items-center justify-center rounded-full border-2 border-line bg-paper-2 text-xs font-bold"
      >
        {entry.author ? initials(entry.author.fullName) : '?'}
      </span>
      <article
        className={cn(
          'min-w-0 flex-1 rounded-md border-2 border-line p-3',
          note ? 'text-status-resolved-fg bg-status-resolved-bg' : 'bg-surface',
        )}
      >
        <header className="mb-1 flex flex-wrap items-center gap-x-2 text-sm">
          <span className="font-semibold">{entry.author?.fullName ?? 'Unknown'}</span>
          {note ? (
            <span className="inline-flex items-center gap-1 text-xs font-bold uppercase">
              <CheckCircle2 aria-hidden="true" className="size-3.5" /> Resolution note
            </span>
          ) : (
            <MessageSquare aria-hidden="true" className="size-3.5 text-muted" />
          )}
          <time
            dateTime={entry.createdAt}
            title={formatDateTime(entry.createdAt)}
            className="text-xs text-muted"
          >
            {pending ? 'sending…' : timeAgo(entry.createdAt)}
          </time>
        </header>
        <p className="text-sm break-words whitespace-pre-wrap">{entry.body}</p>
      </article>
    </li>
  );
}

/** Comments, resolution notes and system events in one list, oldest first. */
export function Timeline({ ticketId }: { ticketId: string }) {
  const timeline = useTimeline(ticketId);
  if (timeline.isError) {
    return (
      <ErrorState
        error={timeline.error}
        onRetry={() => {
          void timeline.refetch();
        }}
      />
    );
  }
  if (!timeline.data) {
    return (
      <div className="flex flex-col gap-3" aria-busy="true" aria-label="Loading timeline">
        <Skeleton className="h-16" />
        <Skeleton className="h-6 w-2/3" />
        <Skeleton className="h-16" />
      </div>
    );
  }
  if (timeline.data.length === 0) {
    return <p className="text-sm text-muted">No activity yet. Be the first to comment.</p>;
  }
  return (
    <ol aria-label="Timeline" className="flex flex-col gap-4">
      {timeline.data.map((entry) => (
        <EntryItem key={entry.id} entry={entry} />
      ))}
    </ol>
  );
}
