import { useEffect, useRef, useState } from 'react';
import { toast } from 'sonner';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router';
import { Bell, CheckCheck } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Dialog } from '@/components/ui/dialog';
import { EmptyState, Skeleton } from '@/components/ui/feedback';
import { api } from '@/lib/api/client';
import type { NotificationPage, NotificationView } from '@/lib/api/types';
import { queryKeys } from '@/lib/query-client';
import { cn, humanize, timeAgo } from '@/lib/utils';

/**
 * Screen 8 (§12.5): the bell with an unread count. Polls every 15 s (ADR-021) and on window focus,
 * so nothing urgent is missed. Opening it shows the latest notifications; clicking one opens the
 * ticket and marks it read.
 */
export function NotificationBell() {
  const [open, setOpen] = useState(false);
  const client = useQueryClient();
  const navigate = useNavigate();

  const unread = useQuery({
    queryKey: queryKeys.notifications.unread,
    queryFn: async () =>
      (await api.get<{ count: number }>('/notifications/unread-count')).data.count,
    refetchInterval: 15_000,
  });

  const list = useQuery({
    queryKey: queryKeys.notifications.list,
    queryFn: async () =>
      (await api.get<NotificationPage>('/notifications', { params: { size: 30 } })).data,
    enabled: open,
  });

  const invalidate = () => client.invalidateQueries({ queryKey: queryKeys.notifications.all });
  const markRead = useMutation({
    mutationFn: (id: string) => api.patch(`/notifications/${id}/read`),
    onSuccess: invalidate,
  });
  const markAll = useMutation({
    mutationFn: () => api.post('/notifications/read-all'),
    onSuccess: invalidate,
  });

  // When new notifications arrive, pop them up and refresh ticket data so every open screen
  // reflects the change at once (not just the counter).
  const seen = useRef<number | null>(null);
  useEffect(() => {
    const count = unread.data;
    if (count === undefined) return;
    const previous = seen.current;
    seen.current = count;
    if (previous === null || count <= previous) return;
    void client.invalidateQueries({ queryKey: queryKeys.tickets.all });
    void client.invalidateQueries({ queryKey: queryKeys.dashboard.all });
    void client.invalidateQueries({ queryKey: queryKeys.notifications.list });
    void api
      .get<NotificationPage>('/notifications', {
        params: { unreadOnly: true, size: Math.min(count - previous, 3) },
      })
      .then(({ data }) => {
        for (const n of data.content) {
          toast(n.message, {
            description: n.ticketNumber,
            action: {
              label: 'Open',
              onClick: () => {
                markRead.mutate(n.id);
                void navigate(`/tickets/${n.ticketId}`);
              },
            },
          });
        }
      })
      .catch(() => undefined);
  }, [unread.data, client, navigate, markRead]);

  const count = unread.data ?? 0;

  const openTicket = (n: NotificationView) => {
    if (!n.read) markRead.mutate(n.id);
    setOpen(false);
    void navigate(`/tickets/${n.ticketId}`);
  };

  return (
    <>
      <Button
        variant="ghost"
        size="icon"
        aria-label={count > 0 ? `Notifications, ${String(count)} unread` : 'Notifications'}
        onClick={() => {
          setOpen(true);
        }}
        className="relative"
      >
        <Bell className="size-5" />
        {count > 0 && (
          <span className="absolute -top-1 -right-1 min-w-5 rounded-full border-2 border-line bg-danger px-1 text-center text-[11px] leading-4 font-bold text-on-danger">
            {count > 99 ? '99+' : count}
          </span>
        )}
      </Button>
      <Dialog
        open={open}
        onClose={() => {
          setOpen(false);
        }}
        title="Notifications"
        footer={
          <Button
            variant="secondary"
            size="sm"
            icon={<CheckCheck className="size-4" />}
            disabled={count === 0}
            loading={markAll.isPending}
            onClick={() => {
              markAll.mutate();
            }}
          >
            Mark all as read
          </Button>
        }
      >
        {list.isPending ? (
          <div className="flex flex-col gap-2">
            {[0, 1, 2].map((i) => (
              <Skeleton key={i} className="h-14" />
            ))}
          </div>
        ) : list.data?.content.length ? (
          <ul className="flex max-h-[60vh] flex-col gap-2 overflow-y-auto">
            {list.data.content.map((n) => (
              <li key={n.id}>
                <button
                  type="button"
                  onClick={() => {
                    openTicket(n);
                  }}
                  className={cn(
                    'w-full rounded-md border-2 px-3 py-2 text-left text-sm transition-colors hover:bg-paper-2',
                    n.read ? 'border-line-soft text-muted' : 'border-line bg-surface font-medium',
                  )}
                >
                  <span className="flex items-center justify-between gap-2">
                    <span className="text-xs font-bold tracking-wide uppercase">
                      {humanize(n.type)}
                    </span>
                    <time dateTime={n.createdAt} className="text-xs text-muted">
                      {timeAgo(n.createdAt)}
                    </time>
                  </span>
                  <span className="mt-1 block">{n.message}</span>
                </button>
              </li>
            ))}
          </ul>
        ) : (
          <EmptyState
            title="You’re all caught up"
            description="Assignments, escalations and SLA breaches show up here."
          />
        )}
      </Dialog>
    </>
  );
}
