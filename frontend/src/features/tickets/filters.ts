import {
  CATEGORIES,
  PRIORITIES,
  STATUSES,
  type Category,
  type Priority,
  type TicketStatus,
} from '@/lib/api/types';

/**
 * Ticket list filters live in the URL (§12.5): shareable links, and the back button undoes a
 * filter change. These two functions are the only translation between the URL and the API query.
 */
export const SORTS = [
  { value: 'createdAt,desc', label: 'Newest first' },
  { value: 'createdAt,asc', label: 'Oldest first' },
  { value: 'priority,desc', label: 'Priority (critical first)' },
  { value: 'slaDueAt,asc', label: 'SLA due soonest' },
  { value: 'updatedAt,desc', label: 'Recently updated' },
  { value: 'status,asc', label: 'Status' },
] as const;

export type SortValue = (typeof SORTS)[number]['value'];
export const PAGE_SIZES = [10, 20, 50] as const;

export interface TicketQuery {
  status?: TicketStatus[];
  priority?: Priority[];
  category?: Category[];
  assigneeId?: string;
  unassigned?: true;
  mine?: true;
  escalated?: true;
  slaBreached?: true;
  q?: string;
  sort: SortValue;
  page: number;
  size: number;
}

function oneOf<T extends string>(allowed: readonly T[], values: string[]): T[] {
  return values.filter((v): v is T => (allowed as readonly string[]).includes(v));
}

/** URL → API query. Unknown values are dropped rather than sent (the API would answer 400). */
export function parseQuery(params: URLSearchParams, defaultSize = 20): TicketQuery {
  const query: TicketQuery = { sort: 'createdAt,desc', page: 0, size: defaultSize };
  const status = oneOf(STATUSES, params.getAll('status'));
  const priority = oneOf(PRIORITIES, params.getAll('priority'));
  const category = oneOf(CATEGORIES, params.getAll('category'));
  if (status.length) query.status = status;
  if (priority.length) query.priority = priority;
  if (category.length) query.category = category;
  const assigneeId = params.get('assigneeId');
  if (assigneeId) query.assigneeId = assigneeId;
  for (const flag of ['unassigned', 'mine', 'escalated', 'slaBreached'] as const) {
    if (params.get(flag) === 'true') query[flag] = true;
  }
  const q = params.get('q')?.trim();
  if (q) query.q = q;
  const sort = params.get('sort');
  if (sort && SORTS.some((s) => s.value === sort)) query.sort = sort as SortValue;
  const page = Number(params.get('page'));
  if (Number.isInteger(page) && page > 0) query.page = page;
  const size = Number(params.get('size'));
  if ((PAGE_SIZES as readonly number[]).includes(size)) query.size = size;
  return query;
}

/** API query → URL. Defaults are left out so URLs stay short. */
export function toSearchParams(query: TicketQuery, defaultSize = 20): URLSearchParams {
  const params = new URLSearchParams();
  for (const s of query.status ?? []) params.append('status', s);
  for (const p of query.priority ?? []) params.append('priority', p);
  for (const c of query.category ?? []) params.append('category', c);
  if (query.assigneeId) params.set('assigneeId', query.assigneeId);
  for (const flag of ['unassigned', 'mine', 'escalated', 'slaBreached'] as const) {
    if (query[flag]) params.set(flag, 'true');
  }
  if (query.q) params.set('q', query.q);
  if (query.sort !== 'createdAt,desc') params.set('sort', query.sort);
  if (query.page > 0) params.set('page', String(query.page));
  if (query.size !== defaultSize) params.set('size', String(query.size));
  return params;
}

/** Saved quick views (§12.5). Each one is just a preset URL. */
export const QUICK_VIEWS = [
  { id: 'all', label: 'All tickets', search: '' },
  { id: 'mine', label: 'My open', search: 'mine=true&status=OPEN&status=IN_PROGRESS' },
  {
    id: 'unassigned-critical',
    label: 'Unassigned critical',
    search: 'unassigned=true&priority=CRITICAL',
  },
  { id: 'breached', label: 'Breached', search: 'slaBreached=true&sort=slaDueAt%2Casc' },
  { id: 'escalated', label: 'Escalated', search: 'escalated=true' },
] as const;

export function activeQuickView(params: URLSearchParams): string | undefined {
  const normalised = toSearchParams(parseQuery(params));
  normalised.delete('page');
  normalised.delete('size');
  const current = [...normalised.entries()].sort().join('&');
  return QUICK_VIEWS.find(
    (view) => [...new URLSearchParams(view.search).entries()].sort().join('&') === current,
  )?.id;
}

export function hasFilters(query: TicketQuery): boolean {
  return Boolean(
    query.status ??
    query.priority ??
    query.category ??
    query.assigneeId ??
    query.unassigned ??
    query.mine ??
    query.escalated ??
    query.slaBreached ??
    query.q,
  );
}
