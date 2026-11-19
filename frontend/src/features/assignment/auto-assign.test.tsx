import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it } from 'vitest';
import { useSession } from '@/features/auth/session-store';
import type { AgentRoutingView, AutoAssignOverview } from '@/lib/api/types';
import { renderRoutes } from '@/test/render';
import { leadAgent, server } from '@/test/server';
import AutoAssignPage from './auto-assign-page';

function row(overrides: Partial<AgentRoutingView>): AgentRoutingView {
  return {
    agentId: 'a-1',
    fullName: 'Dev Malhotra',
    role: 'AGENT',
    ai: false,
    autoAssign: true,
    skills: [],
    openTickets: 2,
    load: 5,
    lastAutoAssignedAt: null,
    ...overrides,
  };
}

function overview(overrides: Partial<AutoAssignOverview> = {}): AutoAssignOverview {
  return {
    enabled: false,
    maxOpenPerAgent: 10,
    waiting: 4,
    agents: [
      row({}),
      row({
        agentId: 'ai',
        fullName: 'Unty',
        ai: true,
        autoAssign: false,
        openTickets: 0,
        load: 0,
      }),
    ],
    ...overrides,
  };
}

function renderPage() {
  return renderRoutes([{ path: '/auto-assign', element: <AutoAssignPage /> }], ['/auto-assign']);
}

beforeEach(() => {
  useSession.getState().setSession('t', leadAgent);
});

describe('auto-assign', () => {
  it('switches auto-assign on, keeping the cap', async () => {
    let sent: unknown;
    server.use(
      http.get('/api/v1/auto-assign', () => HttpResponse.json(overview())),
      http.put('/api/v1/auto-assign/settings', async ({ request }) => {
        sent = await request.json();
        return HttpResponse.json(overview({ enabled: true }));
      }),
    );
    const user = userEvent.setup();
    renderPage();

    const toggle = await screen.findByRole('switch', { name: 'Automatic assignment' });
    expect(toggle).toHaveAttribute('aria-checked', 'false');
    expect(screen.getByText('4')).toBeInTheDocument(); // waiting
    await user.click(toggle);

    await waitFor(() => {
      expect(sent).toEqual({ enabled: true, maxOpenPerAgent: 10 });
    });
    expect(
      await screen.findByRole('heading', { name: 'Automatic assignment is on' }),
    ).toBeInTheDocument();
  });

  it('saves the categories an agent handles and marks the AI as routine-only', async () => {
    let sent: unknown;
    server.use(
      http.get('/api/v1/auto-assign', () => HttpResponse.json(overview())),
      http.put('/api/v1/auto-assign/agents/a-1', async ({ request }) => {
        sent = await request.json();
        return HttpResponse.json(overview());
      }),
    );
    const user = userEvent.setup();
    renderPage();

    const group = await screen.findByRole('group', { name: 'Categories Dev Malhotra handles' });
    expect(screen.getAllByText(/handles all of them/)).not.toHaveLength(0);
    await user.click(within(group).getByRole('button', { name: 'Billing' }));
    await waitFor(() => {
      expect(sent).toEqual({ autoAssign: true, skills: ['BILLING'] });
    });
    expect(screen.getByText(/LOW and MEDIUM tickets only/)).toBeInTheDocument();
    expect(screen.getByRole('switch', { name: 'Unty takes automatic work' })).toHaveAttribute(
      'aria-checked',
      'false',
    );
  });

  it('assigns the waiting queue on demand and reports the result', async () => {
    server.use(
      http.get('/api/v1/auto-assign', () => HttpResponse.json(overview())),
      http.post('/api/v1/auto-assign/run', () => HttpResponse.json({ assigned: 3, waiting: 1 })),
    );
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole('button', { name: 'Assign waiting now' }));
    expect(await screen.findByText('Assigned 3 tickets · 1 still waiting')).toBeInTheDocument();
  });
});
