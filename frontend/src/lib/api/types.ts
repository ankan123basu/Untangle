/**
 * Friendly aliases over the types generated from the backend's OpenAPI spec (schema.d.ts).
 * Never hand-write an API type; regenerate with `npm run api:types` (see package.json).
 */
import type { components } from './schema';

type S = components['schemas'];

export type CurrentAgent = S['CurrentAgent'];
export type Permission = CurrentAgent['permissions'][number];
export type Role = CurrentAgent['role'];
export type TokenResponse = S['TokenResponse'];

export type TicketSummary = S['TicketSummary'];
export type TicketDetail = S['TicketDetail'];
export type TicketAction = TicketDetail['allowedActions'][number];
export type DeniedAction = S['DeniedAction'];
export type TicketPage = S['PageResponseTicketSummary'];
export type Priority = TicketSummary['priority'];
export type TicketStatus = TicketSummary['status'];
export type Category = TicketSummary['category'];
export type AgentRef = S['AgentRef'];
export type TimelineEntry = S['Entry'];
export type CreateTicket = S['CreateTicket'];
export type EditTicket = S['EditTicket'];
export type TransitionTicket = S['TransitionTicket'];
export type BulkAssignResult = S['BulkAssignResult'];

export type DashboardSummary = S['Summary'];
export type DayTrend = S['DayTrend'];
export type Workload = S['Workload'];

export type AgentOption = S['AgentOption'];

/** The AI teammate (ADR-028). */
export type AiTeammateState = S['TeammateState'];
export type AiProposal = S['ProposalView'];
export type AiProposalEnvelope = S['ProposalEnvelope'];
export type AgentAdminView = S['AgentAdminView'];
export type CreateAgent = S['CreateAgent'];

export type PartnerView = S['PartnerView'];
export type IssuedSecret = S['IssuedSecret'];
export type DeliveryView = S['DeliveryView'];
export type DeliveryPage = S['PageResponseDeliveryView'];

export type NotificationView = S['NotificationView'];
export type NotificationPage = S['PageResponseNotificationView'];

export type AiStatus = S['Status'];
export type TriageSuggestion = S['TriageSuggestion'];
export type TimelineSummary = S['TimelineSummary'];
export type ReplyDraft = S['ReplyDraft'];

export const PRIORITIES = [
  'CRITICAL',
  'HIGH',
  'MEDIUM',
  'LOW',
] as const satisfies readonly Priority[];
export const STATUSES = [
  'OPEN',
  'IN_PROGRESS',
  'RESOLVED',
  'CLOSED',
] as const satisfies readonly TicketStatus[];
export const CATEGORIES = [
  'BILLING',
  'TECHNICAL',
  'ACCOUNT',
  'REFUND',
  'GENERAL',
  'OTHER',
] as const satisfies readonly Category[];

export type AutoAssignOverview = S['AutoAssignOverview'];
export type AgentRoutingView = S['AgentRoutingView'];
export type AutoAssignRun = S['AutoAssignRun'];
