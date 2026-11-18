import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { renderRoutes } from '@/test/render';
import { TicketStacks } from './ticket-stacks';

/** jsdom has no WebGL, so this exercises the flat stacks, which share the 3D stage's behaviour. */
describe('ticket stacks (interactive)', () => {
  const routes = [
    {
      path: '/',
      element: <TicketStacks counts={{ CRITICAL: 2, HIGH: 1, MEDIUM: 0, LOW: 3 }} />,
    },
    { path: '/tickets', element: <h1>Ticket list</h1> },
  ];

  it('shows the hovered priority and its open count', async () => {
    const user = userEvent.setup();
    const { container } = renderRoutes(routes, ['/']);
    expect(screen.getByText(/drag to turn/i)).toBeInTheDocument();

    const critical = container.querySelector('[data-priority="CRITICAL"]');
    if (!critical) throw new Error('critical stack missing');
    await user.hover(critical);
    expect(screen.getByText(/Critical · 2 open/)).toBeInTheDocument();

    await user.unhover(critical);
    expect(screen.getByText(/drag to turn/i)).toBeInTheDocument();
  });

  it("opens that priority's unresolved tickets on click", async () => {
    const user = userEvent.setup();
    const { container, router } = renderRoutes(routes, ['/']);
    const low = container.querySelector('[data-priority="LOW"]');
    if (!low) throw new Error('low stack missing');
    await user.click(low);

    expect(await screen.findByRole('heading', { name: 'Ticket list' })).toBeInTheDocument();
    expect(router.state.location.search).toBe('?priority=LOW&status=OPEN&status=IN_PROGRESS');
  });
});
