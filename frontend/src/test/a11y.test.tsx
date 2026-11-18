import { screen } from '@testing-library/react';
import axe from 'axe-core';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it } from 'vitest';
import type { RouteObject } from 'react-router';
import { AppShell } from '@/components/layout/app-shell';
import AgentsPage from '@/features/admin/agents-page';
import PartnersPage from '@/features/admin/partners-page';
import WebhookLogsPage from '@/features/admin/webhook-logs-page';
import LoginPage from '@/features/auth/login-page';
import { useSession } from '@/features/auth/session-store';
import DashboardPage from '@/features/dashboard/dashboard-page';
import BoardPage from '@/features/tickets/board-page';
import CreateTicketPage from '@/features/tickets/create-ticket-page';
import TicketDetailPage from '@/features/tickets/ticket-detail-page';
import TicketsPage from '@/features/tickets/tickets-page';
import { adminUser, agentOptions, comment, page, ticketDetail, ticketSummary } from './fixtures';
import { renderRoutes } from './render';
import { server } from './server';

/**
 * Automated accessibility check (Phase 9) of every main screen with axe-core: names, roles,
 * labels, ARIA validity, heading/list structure. Colour contrast is checked by Lighthouse in a
 * real browser instead (jsdom can't compute colours); "region" is skipped because screens are
 * rendered without the app shell's landmarks here.
 */
async function expectAccessible(container: HTMLElement) {
  const results = await axe.run(container, {
    rules: { 'color-contrast': { enabled: false }, region: { enabled: false } },
    resultTypes: ['violations'],
  });
  const summary = results.violations.map(
    (v) => `${v.id} (${v.impact ?? 'n/a'}): ${v.nodes.map((n) => n.target.join(' ')).join(' | ')}`,
  );
  expect(summary).toEqual([]);
}

const detail = ticketDetail({ assignee: { id: adminUser.id, fullName: adminUser.fullName } });

beforeEach(() => {
  useSession.getState().setSession('t', adminUser);
  server.use(
    http.get('/api/v1/agents', () => HttpResponse.json(agentOptions)),
    http.get('/api/v1/ai/status', () => HttpResponse.json({ enabled: true, model: 'test-model' })),
    http.get('/api/v1/tickets', () =>
      HttpResponse.json(
        page([
          ticketSummary(),
          ticketSummary({ id: 'x2', ticketNumber: 'UNT-000043', priority: 'CRITICAL' }),
        ]),
      ),
    ),
    http.get(`/api/v1/tickets/${detail.id}`, () => HttpResponse.json(detail)),
    http.get(`/api/v1/tickets/${detail.id}/comments`, () =>
      HttpResponse.json([
        comment(),
        comment({ id: 'c2', type: 'RESOLUTION_NOTE', body: 'Refund re-issued.' }),
      ]),
    ),
    http.get('/api/v1/dashboard/summary', () =>
      HttpResponse.json({
        unresolved: 12,
        byStatus: { OPEN: 5, IN_PROGRESS: 7, RESOLVED: 1, CLOSED: 3 },
        unresolvedByPriority: { CRITICAL: 2, HIGH: 4, MEDIUM: 4, LOW: 2 },
        slaBreached: 1,
        escalated: 1,
        unassigned: 3,
        myOpen: 2,
        generatedAt: new Date().toISOString(),
      }),
    ),
    http.get('/api/v1/dashboard/trends', () =>
      HttpResponse.json([{ date: '2026-11-18', created: 3, resolved: 1 }]),
    ),
    http.get('/api/v1/dashboard/agents-workload', () =>
      HttpResponse.json([
        {
          agentId: adminUser.id,
          fullName: adminUser.fullName,
          role: 'ADMIN',
          open: 1,
          inProgress: 1,
          breached: 0,
        },
      ]),
    ),
    http.get('/api/v1/admin/agents', () =>
      HttpResponse.json([
        {
          id: adminUser.id,
          fullName: adminUser.fullName,
          email: adminUser.email,
          role: 'ADMIN',
          active: true,
          locked: false,
          lastLoginAt: null,
          createdAt: '2026-11-01T00:00:00Z',
          version: 0,
        },
      ]),
    ),
    http.get('/api/v1/admin/partners', () =>
      HttpResponse.json([
        {
          id: 'p1',
          code: 'acme-bank',
          name: 'Acme Bank',
          active: true,
          rateLimitPerMinute: 120,
          previousSecretValidUntil: null,
          createdAt: '2026-11-01T00:00:00Z',
          updatedAt: '2026-11-01T00:00:00Z',
        },
      ]),
    ),
    http.get('/api/v1/admin/webhook-deliveries', () =>
      HttpResponse.json({
        content: [
          {
            id: 'd1',
            receivedAt: '2026-11-18T10:00:00Z',
            partnerCode: 'acme-bank',
            eventId: 'evt-1',
            outcome: 'CREATED',
            reason: null,
            ticketId: detail.id,
            ticketNumber: detail.ticketNumber,
            sourceIp: '10.0.0.1',
            payloadSha256: null,
          },
        ],
        page: 0,
        size: 50,
        totalElements: 1,
        totalPages: 1,
      }),
    ),
  );
});

const screens: { name: string; route: RouteObject; url: string; ready: () => Promise<unknown> }[] =
  [
    {
      name: 'app shell (navigation, header, palette, bell)',
      route: {
        path: '/',
        element: <AppShell />,
        children: [{ index: true, element: <p>Home</p> }],
      },
      url: '/',
      ready: () => screen.findByRole('navigation', { name: 'Main' }),
    },
    {
      name: 'login',
      route: { path: '/login', element: <LoginPage /> },
      url: '/login',
      ready: () => screen.findByRole('button', { name: /sign in/i }),
    },
    {
      name: 'dashboard',
      route: { path: '/', element: <DashboardPage /> },
      url: '/',
      ready: () => screen.findByRole('heading', { name: 'Agent workload' }),
    },
    {
      name: 'tickets',
      route: { path: '/tickets', element: <TicketsPage /> },
      url: '/tickets',
      ready: () => screen.findAllByRole('link', { name: 'UNT-000043' }),
    },
    {
      name: 'ticket detail',
      route: { path: '/tickets/:id', element: <TicketDetailPage /> },
      url: `/tickets/${detail.id}`,
      ready: () => screen.findByText('Refund re-issued.'),
    },
    {
      name: 'create ticket',
      route: { path: '/tickets/new', element: <CreateTicketPage /> },
      url: '/tickets/new',
      ready: () => screen.findByRole('button', { name: 'Suggest with AI' }),
    },
    {
      name: 'board',
      route: { path: '/board', element: <BoardPage /> },
      url: '/board',
      ready: async () => (await screen.findAllByRole('link', { name: 'UNT-000043' }))[0],
    },
    {
      name: 'agents',
      route: { path: '/admin/agents', element: <AgentsPage /> },
      url: '/admin/agents',
      ready: () => screen.findByText(adminUser.email),
    },
    {
      name: 'partners',
      route: { path: '/admin/partners', element: <PartnersPage /> },
      url: '/admin/partners',
      ready: () => screen.findByText('acme-bank'),
    },
    {
      name: 'webhook logs',
      route: { path: '/admin/webhooks', element: <WebhookLogsPage /> },
      url: '/admin/webhooks',
      ready: () => screen.findByText('evt-1'),
    },
  ];

describe('accessibility (axe-core)', () => {
  it.each(screens)('$name has no violations', async ({ route, url, ready }) => {
    if (url === '/login') useSession.getState().clear();
    const { container } = renderRoutes([route], [url]);
    await ready();
    await expectAccessible(container);
  });
});
