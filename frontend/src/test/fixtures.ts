import type {
  AgentOption,
  CurrentAgent,
  TicketDetail,
  TicketPage,
  TicketSummary,
  TimelineEntry,
} from '@/lib/api/types';
import { leadAgent } from './server';

/** Realistic API objects for tests. Override only what a test cares about. */

export const agentUser: CurrentAgent = {
  id: '01a1eadf-0000-7000-8000-000000000002',
  fullName: 'Riya Sen',
  email: 'riya.sen@untangle.local',
  role: 'AGENT',
  permissions: [
    'TICKET_VIEW',
    'DASHBOARD_VIEW',
    'TICKET_CREATE',
    'TICKET_SELF_ASSIGN',
    'TICKET_TRANSITION_OWN',
    'TICKET_COMMENT',
    'TICKET_EDIT_OWN',
    'TICKET_ESCALATE',
    'AI_USE',
  ],
};

export const adminUser: CurrentAgent = {
  id: '01a1eadf-0000-7000-8000-000000000003',
  fullName: 'Asha Verma',
  email: 'admin@untangle.local',
  role: 'ADMIN',
  permissions: [...leadAgent.permissions, 'AGENT_MANAGE', 'PARTNER_MANAGE', 'WEBHOOK_LOG_VIEW'],
};

export const agentOptions: AgentOption[] = [
  { id: leadAgent.id, fullName: leadAgent.fullName, role: 'LEAD' },
  { id: agentUser.id, fullName: agentUser.fullName, role: 'AGENT' },
];

const HOUR = 3_600_000;

export function ticketDetail(overrides: Partial<TicketDetail> = {}): TicketDetail {
  const now = Date.now();
  return {
    id: '01a1eb1f-0000-7000-8000-000000000042',
    ticketNumber: 'UNT-000042',
    subject: 'Refund not received',
    description: 'Customer was promised a refund 10 days ago.',
    customerName: 'Priya Nair',
    customerEmail: 'priya@example.com',
    category: 'REFUND',
    priority: 'HIGH',
    status: 'OPEN',
    source: 'MANUAL',
    partnerCode: null,
    externalEventId: null,
    assignee: null,
    createdBy: { id: leadAgent.id, fullName: leadAgent.fullName },
    escalationLevel: 0,
    reopenedCount: 0,
    slaStartedAt: new Date(now - HOUR).toISOString(),
    slaDueAt: new Date(now + 3 * HOUR).toISOString(),
    slaBreached: false,
    slaRemainingSeconds: 3 * 3600,
    firstResponseAt: null,
    resolvedAt: null,
    closedAt: null,
    createdAt: new Date(now - HOUR).toISOString(),
    updatedAt: new Date(now - HOUR).toISOString(),
    version: 3,
    allowedActions: ['EDIT', 'ASSIGN', 'SELF_ASSIGN', 'CLOSE', 'COMMENT', 'ESCALATE'],
    deniedActions: [{ action: 'START_PROGRESS', reason: 'ASSIGNEE_REQUIRED' }],
    ...overrides,
  };
}

export function ticketSummary(overrides: Partial<TicketSummary> = {}): TicketSummary {
  const d = ticketDetail();
  return {
    id: d.id,
    ticketNumber: d.ticketNumber,
    subject: d.subject,
    customerName: d.customerName,
    category: d.category,
    priority: d.priority,
    status: d.status,
    source: d.source,
    assignee: d.assignee,
    escalationLevel: d.escalationLevel,
    slaDueAt: d.slaDueAt,
    slaBreached: d.slaBreached,
    createdAt: d.createdAt,
    updatedAt: d.updatedAt,
    ...overrides,
  };
}

export function page(content: TicketSummary[], extra: Partial<TicketPage> = {}): TicketPage {
  return { content, page: 0, size: 20, totalElements: content.length, totalPages: 1, ...extra };
}

export function comment(overrides: Partial<TimelineEntry> = {}): TimelineEntry {
  return {
    id: '01a1eb20-0000-7000-8000-000000000001',
    type: 'COMMENT',
    body: 'Checked with payments.',
    author: { id: leadAgent.id, fullName: leadAgent.fullName },
    metadata: null,
    createdAt: new Date().toISOString(),
    ...overrides,
  };
}
