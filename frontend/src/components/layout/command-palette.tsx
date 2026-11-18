import { useEffect, useRef, useState } from 'react';
import { Command } from 'cmdk';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router';
import {
  AlertOctagon,
  FileSearch,
  LayoutDashboard,
  ListChecks,
  PlusCircle,
  UserCheck,
} from 'lucide-react';
import { api } from '@/lib/api/client';
import type { TicketPage } from '@/lib/api/types';
import { Kbd } from '@/components/ui/feedback';
import { QUICK_VIEWS } from '@/features/tickets/filters';

function quickViewSearch(id: (typeof QUICK_VIEWS)[number]['id']): string {
  return QUICK_VIEWS.find((v) => v.id === id)?.search ?? '';
}

const TICKET_NUMBER = /^\s*(unt-?)?\d{1,7}\s*$/i;

function normaliseNumber(raw: string): string {
  const digits = raw.replace(/\D/g, '');
  return `UNT-${digits.padStart(6, '0')}`;
}

/**
 * Screen 9 (§12.5): a palette to jump to a ticket by number, search, create or switch views.
 * Loaded on first open (cmdk stays out of the main bundle); the keyboard shortcuts that open it
 * live in use-global-shortcuts.ts.
 */
export default function CommandPalette({
  open,
  onOpenChange,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const navigate = useNavigate();
  const [search, setSearch] = useState('');
  const dialogRef = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    if (open && !dialog.open) {
      dialog.showModal();
      // showModal() doesn't reliably focus the search box; without this, typing went nowhere.
      dialog.querySelector<HTMLInputElement>('[cmdk-input]')?.focus();
    }
    if (!open && dialog.open) dialog.close();
  }, [open]);

  const query = search.trim();
  const results = useQuery({
    queryKey: ['palette', query],
    queryFn: async () =>
      (await api.get<TicketPage>('/tickets', { params: { q: query, size: 6 } })).data,
    enabled: open && query.length >= 2 && !TICKET_NUMBER.test(query),
    staleTime: 10_000,
  });

  const go = (path: string) => {
    onOpenChange(false);
    void navigate(path);
  };

  return (
    <dialog
      ref={dialogRef}
      aria-label="Command palette"
      onClose={() => {
        // Every way of closing (Esc, backdrop, selecting an item) fires the dialog's close event.
        setSearch('');
        onOpenChange(false);
      }}
      onClick={(e) => {
        if (e.target === dialogRef.current) onOpenChange(false);
      }}
      className="solid-panel m-auto mt-[12vh] w-[min(92vw,38rem)] p-0 text-ink backdrop:bg-black/35 backdrop:backdrop-blur-md"
    >
      <Command label="Command palette" shouldFilter={false} className="flex flex-col">
        <Command.Input
          value={search}
          onValueChange={setSearch}
          placeholder="Type a ticket number, search, or a command…"
          className="h-14 border-b border-line bg-transparent px-4 text-base outline-none placeholder:text-muted"
        />
        <Command.List className="max-h-[50vh] overflow-y-auto p-2">
          <Command.Empty className="px-3 py-6 text-center text-sm text-muted">
            No matches.
          </Command.Empty>
          {TICKET_NUMBER.test(query) && (
            <Command.Group heading="Jump to">
              <Item
                onSelect={() => {
                  go(`/tickets/by-number/${normaliseNumber(query)}`);
                }}
                icon={<FileSearch className="size-4" />}
              >
                Open {normaliseNumber(query)}
              </Item>
            </Command.Group>
          )}
          {results.data && results.data.content.length > 0 && (
            <Command.Group heading="Tickets">
              {results.data.content.map((t) => (
                <Item
                  key={t.id}
                  onSelect={() => {
                    go(`/tickets/${t.id}`);
                  }}
                  icon={<span className="font-mono text-xs">{t.ticketNumber}</span>}
                >
                  {t.subject}
                </Item>
              ))}
            </Command.Group>
          )}
          <Command.Group heading="Go to">
            <Item
              onSelect={() => {
                go('/tickets/new');
              }}
              icon={<PlusCircle className="size-4" />}
              hint="C"
            >
              New ticket
            </Item>
            <Item
              onSelect={() => {
                go('/');
              }}
              icon={<LayoutDashboard className="size-4" />}
              hint="G D"
            >
              Dashboard
            </Item>
            <Item
              onSelect={() => {
                go('/tickets');
              }}
              icon={<ListChecks className="size-4" />}
              hint="G T"
            >
              All tickets
            </Item>
            <Item
              onSelect={() => {
                go(`/tickets?${quickViewSearch('mine')}`);
              }}
              icon={<UserCheck className="size-4" />}
            >
              My open tickets
            </Item>
            <Item
              onSelect={() => {
                go(`/tickets?${quickViewSearch('breached')}`);
              }}
              icon={<AlertOctagon className="size-4" />}
            >
              SLA breached
            </Item>
          </Command.Group>
        </Command.List>
      </Command>
    </dialog>
  );
}

function Item({
  children,
  icon,
  hint,
  onSelect,
}: {
  children: React.ReactNode;
  icon: React.ReactNode;
  hint?: string;
  onSelect: () => void;
}) {
  return (
    <Command.Item
      onSelect={onSelect}
      className="flex cursor-pointer items-center gap-3 rounded-md border border-transparent px-3 py-2 text-sm aria-selected:border-line aria-selected:bg-brand-gradient aria-selected:text-on-brand"
    >
      {icon}
      <span className="flex-1 truncate">{children}</span>
      {hint && <Kbd>{hint}</Kbd>}
    </Command.Item>
  );
}
