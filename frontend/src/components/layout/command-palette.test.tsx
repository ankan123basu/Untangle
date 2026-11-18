import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it } from 'vitest';
import { useSession } from '@/features/auth/session-store';
import { renderRoutes } from '@/test/render';
import { leadAgent, server } from '@/test/server';
import { AppShell } from './app-shell';

beforeEach(() => {
  useSession.getState().setSession('t', leadAgent);
  server.use(
    http.get('/api/v1/tickets', () =>
      HttpResponse.json({ content: [], page: 0, size: 6, totalElements: 0, totalPages: 0 }),
    ),
  );
});

describe('command palette', () => {
  it('opens with Ctrl+K, focuses the search box and offers a jump to a ticket number', async () => {
    const user = userEvent.setup();
    const { router } = renderRoutes([
      { path: '/', element: <AppShell />, children: [{ index: true, element: <p>Home</p> }] },
      { path: '/tickets/by-number/:number', element: <p>Lookup</p> },
    ]);
    await screen.findByRole('navigation', { name: 'Main' });

    await user.keyboard('{Control>}k{/Control}');
    const input = await screen.findByPlaceholderText(/ticket number, search/i);
    await waitFor(() => {
      expect(input).toHaveFocus();
    });

    await user.keyboard('51');
    await user.click(await screen.findByText('Open UNT-000051'));
    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/tickets/by-number/UNT-000051');
    });
  });
});
