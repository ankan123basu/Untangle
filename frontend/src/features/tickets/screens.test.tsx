import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it } from 'vitest';
import { useSession } from '@/features/auth/session-store';
import DashboardPage from '@/features/dashboard/dashboard-page';
import { agentOptions, agentUser, page, ticketDetail, ticketSummary } from '@/test/fixtures';
import { renderRoutes } from '@/test/render';
import { leadAgent, problem, server } from '@/test/server';
import CreateTicketPage from './create-ticket-page';
import TicketsPage from './tickets-page';

beforeEach(() => {
  useSession.getState().setSession('t', leadAgent);
  server.use(
    http.get('/api/v1/agents', () => HttpResponse.json(agentOptions)),
    http.get('/api/v1/dashboard/agents-workload', () =>
      HttpResponse.json([
        {
          agentId: leadAgent.id,
          fullName: leadAgent.fullName,
          role: 'LEAD',
          open: 2,
          inProgress: 1,
          breached: 1,
        },
      ]),
    ),
  );
});

describe('tickets list', () => {
  it('sends URL filters to the API and updates the URL when a chip is toggled', async () => {
    const seen: string[] = [];
    server.use(
      http.get('/api/v1/tickets', ({ request }) => {
        seen.push(new URL(request.url).search);
        return HttpResponse.json(page([ticketSummary()]));
      }),
    );
    const user = userEvent.setup();
    const { router } = renderRoutes(
      [{ path: '/tickets', element: <TicketsPage /> }],
      ['/tickets?priority=HIGH'],
    );

    expect(await screen.findAllByRole('link', { name: 'UNT-000042' })).not.toHaveLength(0);
    expect(seen[0]).toContain('priority=HIGH');

    await user.click(screen.getByRole('button', { name: 'Open', pressed: false }));

    await waitFor(() => {
      expect(router.state.location.search).toBe('?status=OPEN&priority=HIGH');
    });
    await waitFor(() => {
      expect(seen.at(-1)).toContain('status=OPEN');
    });
    expect(screen.getByRole('button', { name: 'Open', pressed: true })).toBeInTheDocument();
  });

  it('offers to clear filters when nothing matches', async () => {
    server.use(http.get('/api/v1/tickets', () => HttpResponse.json(page([]))));
    const user = userEvent.setup();
    const { router } = renderRoutes(
      [{ path: '/tickets', element: <TicketsPage /> }],
      ['/tickets?escalated=true'],
    );

    expect(await screen.findByText('No tickets match these filters')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Clear filters' }));
    await waitFor(() => {
      expect(router.state.location.search).toBe('');
    });
  });
});

describe('dashboard', () => {
  beforeEach(() => {
    server.use(
      http.get('/api/v1/dashboard/summary', () =>
        HttpResponse.json({
          unresolved: 38,
          byStatus: { OPEN: 20, IN_PROGRESS: 18, RESOLVED: 5, CLOSED: 40 },
          unresolvedByPriority: { CRITICAL: 6, HIGH: 14, MEDIUM: 12, LOW: 6 },
          slaBreached: 12,
          escalated: 9,
          unassigned: 21,
          myOpen: 3,
          generatedAt: new Date().toISOString(),
        }),
      ),
      http.get('/api/v1/dashboard/trends', () =>
        HttpResponse.json([{ date: '2026-11-18', created: 4, resolved: 2 }]),
      ),
      http.get('/api/v1/tickets', () => HttpResponse.json(page([]))),
    );
  });

  it('shows the unresolved count and KPI cards that link to filtered lists', async () => {
    renderRoutes([{ path: '/', element: <DashboardPage /> }]);

    expect(await screen.findByText('38')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /SLA breached\s*12/ })).toHaveAttribute(
      'href',
      '/tickets?slaBreached=true&sort=slaDueAt%2Casc',
    );
    expect(screen.getByRole('link', { name: /Unassigned\s*21/ })).toHaveAttribute(
      'href',
      '/tickets?unassigned=true',
    );
    expect(await screen.findByRole('heading', { name: 'Agent workload' })).toBeInTheDocument();
  });

  it('hides team workload from agents', async () => {
    useSession.getState().setSession('t', agentUser);
    renderRoutes([{ path: '/', element: <DashboardPage /> }]);

    expect(await screen.findByText('38')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Agent workload' })).not.toBeInTheDocument();
  });
});

describe('create ticket', () => {
  beforeEach(() => {
    server.use(
      http.get('/api/v1/ai/status', () =>
        HttpResponse.json({ enabled: true, model: 'test-model' }),
      ),
    );
  });

  it('validates on the client before sending', async () => {
    let posted = false;
    server.use(
      http.post('/api/v1/tickets', () => {
        posted = true;
        return HttpResponse.json(ticketDetail());
      }),
    );
    const user = userEvent.setup();
    renderRoutes([{ path: '/tickets/new', element: <CreateTicketPage /> }], ['/tickets/new']);

    await user.click(await screen.findByRole('button', { name: 'Create ticket' }));
    expect(await screen.findByText('At least 5 characters')).toBeInTheDocument();
    expect(posted).toBe(false);
  });

  it('accepts AI suggestions per field and shows server field errors inline', async () => {
    let body: Record<string, unknown> | undefined;
    server.use(
      http.post('/api/v1/ai/triage', () =>
        HttpResponse.json({
          insightId: 'ins-1',
          category: 'REFUND',
          priority: 'CRITICAL',
          summary: 'Refund delayed',
          sentiment: 'negative',
          rationale: 'Money owed to the customer',
          model: 'test-model',
          promptVersion: 'v1',
        }),
      ),
      http.patch(
        '/api/v1/ai/insights/ins-1/feedback',
        () => new HttpResponse(null, { status: 204 }),
      ),
      http.post('/api/v1/tickets', async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return problem(400, 'VALIDATION_FAILED', 'Request has 1 invalid field', {
          errors: [{ field: 'customerName', message: 'Name looks invalid' }],
        });
      }),
    );
    const user = userEvent.setup();
    renderRoutes([{ path: '/tickets/new', element: <CreateTicketPage /> }], ['/tickets/new']);

    await user.type(await screen.findByLabelText(/subject/i), 'Refund not received');
    await user.type(screen.getByLabelText(/description/i), 'Promised 10 days ago, still nothing.');
    await user.click(screen.getByRole('button', { name: 'Suggest with AI' }));
    await user.click(await screen.findByRole('button', { name: 'Accept Priority: Critical' }));
    expect(screen.getByLabelText(/^priority/i)).toHaveValue('CRITICAL');
    // Ignored suggestion leaves the field alone.
    await user.click(screen.getByRole('button', { name: 'Ignore Category: Refund' }));
    expect(screen.getByLabelText(/^category/i)).toHaveValue('GENERAL');

    await user.type(screen.getByLabelText(/customer name/i), 'Al');
    await user.click(screen.getByRole('button', { name: 'Create ticket' }));

    expect(await screen.findByText('Name looks invalid')).toBeInTheDocument();
    expect(body).toMatchObject({ priority: 'CRITICAL', category: 'GENERAL', customerName: 'Al' });
    expect(body).not.toHaveProperty('customerEmail');
  });

  it('lets an agent assign only themselves', async () => {
    useSession.getState().setSession('t', agentUser);
    renderRoutes([{ path: '/tickets/new', element: <CreateTicketPage /> }], ['/tickets/new']);

    expect(await screen.findByRole('checkbox', { name: 'Assign to me' })).toBeInTheDocument();
    expect(screen.queryByLabelText('Assign to')).not.toBeInTheDocument();
    expect(within(document.body).queryByText(/open$/)).not.toBeInTheDocument();
  });
});
