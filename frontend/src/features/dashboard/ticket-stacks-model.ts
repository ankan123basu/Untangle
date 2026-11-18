import type { Priority } from '@/lib/api/types';

/** How many tickets one stack shows as slabs; the rest become a "+N more" cap. */
export const SLABS_PER_STACK = 10;

/** One real ticket, as a slab. */
export interface Slab {
  id: string;
  number: string;
  subject: string;
  assignee: string | null;
  breached: boolean;
  slaDueAt: string;
}

/** One priority's stack: its most urgent tickets and how many there are in all. */
export interface StackData {
  priority: Priority;
  slabs: Slab[];
  total: number;
}

/** What the pointer is on: one ticket, the "+N more" cap, or a stack's base. */
export type Target =
  | { kind: 'ticket'; priority: Priority; slab: Slab }
  | { kind: 'more'; priority: Priority; rest: number }
  | { kind: 'stack'; priority: Priority };

export function sameTarget(a: Target | null, b: Target | null): boolean {
  if (!a || !b) return a === b;
  if (a.kind !== b.kind || a.priority !== b.priority) return false;
  return a.kind === 'ticket' && b.kind === 'ticket' ? a.slab.id === b.slab.id : true;
}
