import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it } from 'vitest';
import type { Priority, TicketSummary } from '@/lib/api/types';
import { server } from '@/test/server';
import { renderRoutes } from '@/test/render';
import { TicketStacks } from './ticket-stacks';

/** A queue with 2 critical, 1 high, 0 medium and 25 low unresolved tickets. */
const TOTALS: Record<Priority, number> = { CRITICAL: 2, HIGH: 1, MEDIUM: 0, LOW: 25 };

function ticket(priority: Priority, i: number): TicketSummary {
  const n = `${priority}-${String(i)}`;
  return {
    id: `id-${n}`,
    ticketNumber: `UNT-${String(1000 + i).padStart(6, '0')}`,
    subject: `${priority} case ${String(i)}`,
    customerName: 'Kiran Rao',
    category: 'BILLING',
    priority,
    status: 'OPEN',
    source: 'MANUAL',
    assignee: i === 0 ? { id: 'a1', fullName: 'Riya Sen', ai: false } : null,
    escalationLevel: 0,
    slaDueAt: '2099-01-01T00:00:00Z',
    slaBreached: i === 1,
    createdAt: '2026-11-18T10:00:00Z',
    updatedAt: '2026-11-18T10:00:00Z',
  };
}

/**
 * jsdom has no WebGL, so these exercise the flat stacks, which share the 3D stage's data and
 * behaviour: one slab per real ticket, a "+N more" cap, hover read-out and click targets.
 */
describe('ticket stacks (interactive, one slab per ticket)', () => {
  beforeEach(() => {
    server.use(
      http.get('/api/v1/tickets', ({ request }) => {
        const url = new URL(request.url);
        const priority = url.searchParams.get('priority') as Priority;
        const size = Number(url.searchParams.get('size'));
        const total = TOTALS[priority];
        const content = Array.from({ length: Math.min(size, total) }, (_, i) =>
          ticket(priority, i),
        );
        return HttpResponse.json({
          content,
          page: 0,
          size,
          totalElements: total,
          totalPages: Math.ceil(total / size),
        });
      }),
    );
  });

  const routes = [
    { path: '/', element: <TicketStacks /> },
    { path: '/tickets', element: <h1>Ticket list</h1> },
    { path: '/tickets/:id', element: <h1>Ticket detail</h1> },
  ];

  it('draws every ticket as its own slab and caps a big stack with "+N more"', async () => {
    const { container } = renderRoutes(routes, ['/']);
    await waitFor(() => {
      expect(container.querySelectorAll('[data-priority="LOW"] [data-ticket]')).toHaveLength(10);
    });
    expect(container.querySelectorAll('[data-priority="CRITICAL"] [data-ticket]')).toHaveLength(2);
    expect(container.querySelector('[data-priority="LOW"] [data-target="more"]')).toHaveTextContent(
      '+15',
    );
    expect(container.querySelector('[data-priority="CRITICAL"] [data-target="more"]')).toBeNull();
  });

  it('reads out the hovered ticket and opens it on click', async () => {
    const user = userEvent.setup();
    const { container, router } = renderRoutes(routes, ['/']);
    await waitFor(() => {
      expect(container.querySelector('[data-priority="CRITICAL"] [data-ticket]')).not.toBeNull();
    });
    const slabs = container.querySelectorAll('[data-priority="CRITICAL"] [data-ticket]');
    const [first, second] = [slabs[0], slabs[1]];
    if (!first || !second) throw new Error('slabs missing');

    await user.hover(first);
    const readout = screen.getByTestId('stacks-readout');
    expect(readout).toHaveTextContent('UNT-001000');
    expect(readout).toHaveTextContent('CRITICAL case 0');
    expect(readout).toHaveTextContent('Riya Sen');

    await user.hover(second);
    expect(readout).toHaveTextContent('Unassigned');
    expect(readout).toHaveTextContent('SLA breached');

    await user.click(second);
    expect(await screen.findByRole('heading', { name: 'Ticket detail' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/tickets/id-CRITICAL-1');
  });

  it('opens the whole priority list from the "+N more" cap', async () => {
    const user = userEvent.setup();
    const { container, router } = renderRoutes(routes, ['/']);
    const more = await waitFor(() => {
      const el = container.querySelector('[data-priority="LOW"] [data-target="more"]');
      if (!el) throw new Error('cap missing');
      return el;
    });
    await user.hover(more);
    expect(screen.getByTestId('stacks-readout')).toHaveTextContent('+15 more low');
    await user.click(more);
    expect(await screen.findByRole('heading', { name: 'Ticket list' })).toBeInTheDocument();
    expect(router.state.location.search).toBe('?priority=LOW&status=OPEN&status=IN_PROGRESS');
  });
});
