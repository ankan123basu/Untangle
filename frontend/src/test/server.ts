import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import type { CurrentAgent, TokenResponse } from '@/lib/api/types';

/** A realistic signed-in lead, matching the backend's CurrentAgent shape. */
export const leadAgent: CurrentAgent = {
  id: '01a1eadf-0000-7000-8000-000000000001',
  fullName: 'Leela Thomas',
  email: 'leela.thomas@untangle.local',
  role: 'LEAD',
  permissions: [
    'TICKET_VIEW',
    'DASHBOARD_VIEW',
    'TICKET_CREATE',
    'TICKET_SELF_ASSIGN',
    'TICKET_ASSIGN_ANY',
    'TICKET_TRANSITION_OWN',
    'TICKET_TRANSITION_ANY',
    'TICKET_CLOSE',
    'TICKET_CLOSE_OWN_RESOLVED',
    'TICKET_REOPEN',
    'TICKET_COMMENT',
    'TICKET_EDIT_OWN',
    'TICKET_EDIT_ANY',
    'TICKET_ESCALATE',
    'AGENT_WORKLOAD_VIEW',
    'AI_USE',
  ],
};

export function tokenResponse(agent: CurrentAgent = leadAgent, token = 'token-1'): TokenResponse {
  return {
    accessToken: token,
    tokenType: 'Bearer',
    expiresIn: 900,
    expiresAt: new Date(Date.now() + 900_000).toISOString(),
    agent,
  };
}

/** RFC 9457 problem body exactly as the backend sends it. */
export function problem(
  status: number,
  code: string,
  detail: string,
  extra: Record<string, unknown> = {},
) {
  return HttpResponse.json(
    {
      type: `urn:untangle:problem:${code.toLowerCase()}`,
      title: code,
      status,
      detail,
      code,
      traceId: 'trace-123',
      ...extra,
    },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  );
}

/** Default handlers; individual tests override with server.use(...). */
export const server = setupServer(
  http.post('/api/v1/auth/refresh', () => problem(401, 'AUTH_REFRESH_INVALID', 'No session')),
  http.get('/api/v1/notifications/unread-count', () => HttpResponse.json({ count: 0 })),
);
