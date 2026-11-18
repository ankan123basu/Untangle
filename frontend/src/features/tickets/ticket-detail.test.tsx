import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it } from 'vitest';
import { useSession } from '@/features/auth/session-store';
import type { TicketDetail, TimelineEntry } from '@/lib/api/types';
import { agentOptions, comment, ticketDetail } from '@/test/fixtures';
import { renderRoutes } from '@/test/render';
import { leadAgent, problem, server } from '@/test/server';
import TicketDetailPage from './ticket-detail-page';

const ID = ticketDetail().id;

function serve(detail: TicketDetail, timeline: TimelineEntry[] = []) {
  server.use(
    http.get(`/api/v1/tickets/${ID}`, () => HttpResponse.json(detail)),
    http.get(`/api/v1/tickets/${ID}/comments`, () => HttpResponse.json(timeline)),
    http.get('/api/v1/agents', () => HttpResponse.json(agentOptions)),
    http.get('/api/v1/ai/status', () => HttpResponse.json({ enabled: false, model: null })),
    http.get('/api/v1/dashboard/agents-workload', () => HttpResponse.json([])),
  );
}

function renderDetail() {
  return renderRoutes(
    [{ path: '/tickets/:id', element: <TicketDetailPage /> }],
    [`/tickets/${ID}`],
  );
}

beforeEach(() => {
  useSession.getState().setSession('t', leadAgent);
});

describe('ticket detail', () => {
  it('builds the action bar from allowedActions and explains disabled ones', async () => {
    serve(ticketDetail());
    renderDetail();

    const toolbar = await screen.findByRole('toolbar', { name: 'Ticket actions' });
    expect(within(toolbar).getByRole('button', { name: 'Close' })).toBeEnabled();
    expect(within(toolbar).getByRole('button', { name: 'Escalate' })).toBeInTheDocument();
    // Denied with a reason → shown, marked disabled, with the reason as its description.
    const start = within(toolbar).getByRole('button', { name: 'Start progress' });
    expect(start).toHaveAttribute('aria-disabled', 'true');
    expect(start).toHaveAccessibleDescription('Assign someone first.');
    // Not offered at all from this status → not shown.
    expect(within(toolbar).queryByRole('button', { name: 'Resolve' })).not.toBeInTheDocument();
  });

  it("hides what an agent may never do on someone else's ticket and says it is view only", async () => {
    serve(
      ticketDetail({
        status: 'IN_PROGRESS',
        assignee: { id: '01a1eb1f-0000-7000-8000-0000000000d1', fullName: 'Dev Malhotra' },
        allowedActions: ['COMMENT', 'ESCALATE'],
        deniedActions: [
          { action: 'EDIT', reason: 'ACCESS_DENIED' },
          { action: 'ASSIGN', reason: 'ACCESS_DENIED' },
          { action: 'RESOLVE', reason: 'ACCESS_DENIED' },
          { action: 'RELEASE', reason: 'ACCESS_DENIED' },
          { action: 'CLOSE', reason: 'ACCESS_DENIED' },
        ],
      }),
    );
    renderDetail();

    const toolbar = await screen.findByRole('toolbar', { name: 'Ticket actions' });
    for (const name of ['Edit', 'Resolve', 'Close']) {
      expect(within(toolbar).queryByRole('button', { name })).not.toBeInTheDocument();
    }
    expect(within(toolbar).getByRole('button', { name: 'Escalate' })).toBeInTheDocument();
    expect(screen.getByRole('note')).toHaveTextContent(/View only: this is Dev Malhotra’s ticket/);
  });

  it('renders HTML in comments as plain text (§11)', async () => {
    const evil = '<img src=x onerror="alert(1)"><b>bold</b>';
    serve(ticketDetail(), [comment({ body: evil })]);
    const { container } = renderDetail();

    expect(await screen.findByText(evil)).toBeInTheDocument();
    expect(container.querySelector('img')).toBeNull();
    expect(container.querySelector('b')).toBeNull();
  });

  it('requires a resolution note and sends it with the loaded version', async () => {
    const detail = ticketDetail({
      status: 'IN_PROGRESS',
      assignee: { id: leadAgent.id, fullName: leadAgent.fullName },
      allowedActions: ['RESOLVE', 'RELEASE', 'COMMENT', 'EDIT'],
      deniedActions: [],
    });
    serve(detail);
    let sent: unknown;
    server.use(
      http.post(`/api/v1/tickets/${ID}/transitions`, async ({ request }) => {
        sent = await request.json();
        return HttpResponse.json({ ...detail, status: 'RESOLVED', version: 4, allowedActions: [] });
      }),
    );
    const user = userEvent.setup();
    renderDetail();

    await user.click(await screen.findByRole('button', { name: 'Resolve' }));
    const dialog = await screen.findByRole('dialog', { name: 'Resolve' });
    await user.click(within(dialog).getByRole('button', { name: 'Resolve' }));
    expect(await within(dialog).findByText('Write a resolution note')).toBeInTheDocument();
    expect(sent).toBeUndefined();

    await user.type(
      within(dialog).getByLabelText(/resolution note/i),
      'Refund re-issued by payments.',
    );
    await user.click(within(dialog).getByRole('button', { name: 'Resolve' }));

    await waitFor(() => {
      expect(sent).toEqual({
        targetStatus: 'RESOLVED',
        version: 3,
        note: 'Refund re-issued by payments.',
      });
    });
    expect(await screen.findByText('UNT-000042 → Resolved')).toBeInTheDocument();
  });

  it('shows the conflict dialog on VERSION_CONFLICT and reloads on request', async () => {
    const detail = ticketDetail({
      status: 'IN_PROGRESS',
      allowedActions: ['RELEASE', 'COMMENT'],
      deniedActions: [],
    });
    serve(detail);
    let loads = 0;
    server.use(
      http.get(`/api/v1/tickets/${ID}`, () => {
        loads++;
        return HttpResponse.json(detail);
      }),
      http.post(`/api/v1/tickets/${ID}/transitions`, () =>
        problem(409, 'VERSION_CONFLICT', 'Ticket was modified'),
      ),
    );
    const user = userEvent.setup();
    renderDetail();

    await user.click(await screen.findByRole('button', { name: 'Release to queue' }));
    const dialog = await screen.findByRole('dialog', { name: 'This ticket changed' });
    const before = loads;
    await user.click(within(dialog).getByRole('button', { name: 'Load latest' }));
    await waitFor(() => {
      expect(loads).toBeGreaterThan(before);
    });
  });

  it('makes a closed ticket read-only with a Reopen button for leads', async () => {
    serve(
      ticketDetail({
        status: 'CLOSED',
        allowedActions: ['REOPEN'],
        deniedActions: [{ action: 'COMMENT', reason: 'TICKET_CLOSED' }],
      }),
    );
    renderDetail();

    expect(await screen.findByText('This ticket is closed and read-only.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Reopen' })).toBeInTheDocument();
    expect(screen.queryByRole('form', { name: 'Add a comment' })).not.toBeInTheDocument();
    expect(screen.queryByRole('toolbar')).not.toBeInTheDocument();
  });

  it('shows a comment at once and gives the text back if the server rejects it', async () => {
    serve(ticketDetail());
    server.use(
      http.post(`/api/v1/tickets/${ID}/comments`, () => problem(409, 'TICKET_CLOSED', 'closed')),
    );
    const user = userEvent.setup();
    renderDetail();

    const box = await screen.findByRole('textbox', { name: 'Comment' });
    await user.type(box, 'On it');
    await user.keyboard('{Control>}{Enter}{/Control}');

    await waitFor(() => {
      expect(box).toHaveValue('On it');
    });
    expect(await screen.findAllByText(/ticket is closed/i)).not.toHaveLength(0);
  });
});
