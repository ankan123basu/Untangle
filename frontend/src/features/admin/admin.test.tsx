import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it } from 'vitest';
import { useSession } from '@/features/auth/session-store';
import type { AgentAdminView, PartnerView } from '@/lib/api/types';
import { adminUser, page, ticketSummary } from '@/test/fixtures';
import { renderRoutes } from '@/test/render';
import { problem, server } from '@/test/server';
import AgentsPage from './agents-page';
import PartnersPage from './partners-page';

const partner: PartnerView = {
  id: 'p-1',
  code: 'acme-bank',
  name: 'Acme Bank',
  active: true,
  rateLimitPerMinute: 120,
  previousSecretValidUntil: null,
  createdAt: '2026-11-01T00:00:00Z',
  updatedAt: '2026-11-01T00:00:00Z',
};

function agent(overrides: Partial<AgentAdminView>): AgentAdminView {
  return {
    id: 'a-1',
    fullName: 'Dev Malhotra',
    email: 'dev.malhotra@untangle.local',
    role: 'AGENT',
    active: true,
    locked: false,
    lastLoginAt: null,
    createdAt: '2026-11-01T00:00:00Z',
    version: 0,
    ...overrides,
  };
}

beforeEach(() => {
  useSession.getState().setSession('t', adminUser);
});

describe('partners', () => {
  it('shows a rotated secret once and forgets it when the dialog closes', async () => {
    server.use(
      http.get('/api/v1/admin/partners', () => HttpResponse.json([partner])),
      http.post('/api/v1/admin/partners/p-1/rotate-secret', () =>
        HttpResponse.json({
          partner: { ...partner, previousSecretValidUntil: '2099-01-01T00:00:00Z' },
          secret: 'whsec_test_value_123',
          notice: 'Store this secret now; it will not be shown again.',
        }),
      ),
    );
    const user = userEvent.setup();
    renderRoutes([{ path: '/', element: <PartnersPage /> }]);

    await user.click(await screen.findByRole('button', { name: 'Rotate secret' }));
    await user.click(
      within(await screen.findByRole('dialog', { name: /rotate acme-bank/i })).getByRole('button', {
        name: 'Rotate',
      }),
    );

    const secretDialog = await screen.findByRole('dialog', {
      name: /signing secret for acme-bank/i,
    });
    expect(within(secretDialog).getByLabelText('Signing secret')).toHaveTextContent(
      'whsec_test_value_123',
    );

    await user.click(within(secretDialog).getByRole('button', { name: 'I’ve stored it safely' }));
    await waitFor(() => {
      expect(screen.queryByText('whsec_test_value_123')).not.toBeInTheDocument();
    });
  });

  it('validates the partner code like the server does', async () => {
    server.use(http.get('/api/v1/admin/partners', () => HttpResponse.json([])));
    const user = userEvent.setup();
    renderRoutes([{ path: '/', element: <PartnersPage /> }]);

    await user.click(await screen.findByRole('button', { name: 'Add partner' }));
    const dialog = await screen.findByRole('dialog', { name: 'Add a partner' });
    await user.type(within(dialog).getByLabelText(/^code/i), 'Acme Bank!');
    await user.click(within(dialog).getByRole('button', { name: 'Create and show secret' }));
    expect(
      await within(dialog).findByText(/lowercase letters, digits and dashes/i),
    ).toBeInTheDocument();
  });
});

describe('agents', () => {
  it('moves open tickets to a colleague before deactivating someone', async () => {
    const dev = agent({});
    const zoya = agent({ id: 'a-2', fullName: 'Zoya Ali', email: 'zoya@untangle.local' });
    let bulk: unknown;
    let deactivations = 0;
    server.use(
      http.get('/api/v1/admin/agents', () => HttpResponse.json([dev, zoya])),
      http.patch('/api/v1/agents/a-1/active', () => {
        deactivations++;
        return deactivations === 1
          ? problem(409, 'AGENT_HAS_OPEN_TICKETS', 'Agent has 1 open ticket')
          : HttpResponse.json({ ...dev, active: false });
      }),
      http.get('/api/v1/tickets', ({ request }) => {
        const url = new URL(request.url);
        expect(url.searchParams.get('assigneeId')).toBe('a-1');
        expect(url.searchParams.getAll('status')).toEqual(['OPEN', 'IN_PROGRESS']);
        return HttpResponse.json(page([ticketSummary({ id: 't-1' })]));
      }),
      http.post('/api/v1/tickets/bulk/assign', async ({ request }) => {
        bulk = await request.json();
        return HttpResponse.json({ assigned: ['t-1'], skipped: [] });
      }),
    );
    const user = userEvent.setup();
    renderRoutes([{ path: '/', element: <AgentsPage /> }]);

    const row = (await screen.findByText('Dev Malhotra')).closest('tr');
    expect(row).not.toBeNull();
    if (!row) return;
    await user.click(within(row).getByRole('button', { name: 'Deactivate' }));

    const dialog = await screen.findByRole('dialog', { name: /reassign dev malhotra/i });
    await user.selectOptions(within(dialog).getByLabelText(/move open tickets to/i), 'a-2');
    await user.click(within(dialog).getByRole('button', { name: 'Move tickets and deactivate' }));

    await waitFor(() => {
      expect(deactivations).toBe(2);
    });
    expect(bulk).toEqual({ ticketIds: ['t-1'], agentId: 'a-2' });
  });
});
