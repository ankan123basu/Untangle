import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it } from 'vitest';
import { useSession } from '@/features/auth/session-store';
import type { AiProposal, TicketDetail } from '@/lib/api/types';
import { agentOptions, agentUser, ticketDetail } from '@/test/fixtures';
import { renderRoutes } from '@/test/render';
import { leadAgent, server } from '@/test/server';
import TicketDetailPage from './ticket-detail-page';

const ID = ticketDetail().id;
const AI = { id: '01a1f000-0000-7000-8000-0000000000a1', fullName: 'Untangle AI', ai: true };

function proposal(overrides: Partial<AiProposal> = {}): AiProposal {
  return {
    id: 'p-1',
    reply: 'Sorry about the refund delay; it has been re-issued.',
    resolutionNote:
      'Refund was stuck at the bank; re-issued and customer told to expect it in 2 days.',
    confidence: 0.86,
    status: 'PENDING',
    model: 'fake-model',
    promptVersion: 'teammate-v1',
    createdAt: new Date().toISOString(),
    decidedBy: null,
    decidedAt: null,
    canDecide: true,
    ...overrides,
  };
}

function serve(detail: TicketDetail, current: AiProposal | null) {
  server.use(
    http.get(`/api/v1/tickets/${ID}`, () => HttpResponse.json(detail)),
    http.get(`/api/v1/tickets/${ID}/comments`, () => HttpResponse.json([])),
    http.get('/api/v1/agents', () =>
      HttpResponse.json([...agentOptions, { ...AI, role: 'AGENT' }]),
    ),
    http.get('/api/v1/ai/status', () => HttpResponse.json({ enabled: true, model: 'fake-model' })),
    http.get('/api/v1/dashboard/agents-workload', () => HttpResponse.json([])),
    http.get(`/api/v1/tickets/${ID}/ai-proposal`, () => HttpResponse.json({ proposal: current })),
  );
}

function renderDetail() {
  return renderRoutes(
    [{ path: '/tickets/:id', element: <TicketDetailPage /> }],
    [`/tickets/${ID}`],
  );
}

/** The AI teammate on a ticket (ADR-028): it proposes, a lead decides. */
describe('AI teammate panel', () => {
  beforeEach(() => {
    useSession.getState().setSession('t', leadAgent);
  });

  it('shows the AI working while it drafts', async () => {
    serve(ticketDetail({ assignee: AI }), null);
    renderDetail();

    expect(await screen.findByRole('heading', { name: 'Untangle AI' })).toBeInTheDocument();
    expect(screen.getByText(/reading the ticket and drafting a reply/i)).toBeInTheDocument();
  });

  it('lets a lead approve the proposal, which resolves the ticket', async () => {
    const user = userEvent.setup();
    let sent: unknown = null;
    serve(ticketDetail({ assignee: AI, status: 'IN_PROGRESS', version: 7 }), proposal());
    server.use(
      http.post(`/api/v1/tickets/${ID}/ai-proposal/approve`, async ({ request }) => {
        sent = await request.json();
        return HttpResponse.json(proposal({ status: 'APPROVED', decidedBy: leadAgent.fullName }));
      }),
    );
    renderDetail();

    expect(await screen.findByText(/re-issued and customer told/)).toBeInTheDocument();
    expect(screen.getByRole('meter', { name: 'AI confidence' })).toHaveAttribute(
      'aria-valuenow',
      '86',
    );
    await user.click(screen.getByRole('button', { name: /approve & resolve/i }));

    await waitFor(() => {
      expect(sent).toEqual({ version: 7 });
    });
  });

  it('tells a non-lead it is waiting for a lead, with no decision buttons', async () => {
    useSession.getState().setSession('t', agentUser);
    serve(ticketDetail({ assignee: AI, status: 'IN_PROGRESS' }), proposal({ canDecide: false }));
    renderDetail();

    expect(await screen.findByText(/waiting for a lead/i)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /approve & resolve/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /take over/i })).not.toBeInTheDocument();
  });
});
