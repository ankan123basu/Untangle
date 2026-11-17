import type { CSSProperties } from 'react';

/**
 * Inline style for a staggered list entrance (pairs with the `animate-rise` utility). Only the
 * first `cap` items are delayed, so long lists never feel slow.
 */
export function stagger(index: number, step = 35, cap = 10): CSSProperties {
  return { animationDelay: `${String(Math.min(index, cap) * step)}ms` };
}

/** view-transition-name for a ticket number, shared by the list row and the detail header. */
export function ticketTransitionName(id: string): string {
  return `ticket-${id}`;
}
