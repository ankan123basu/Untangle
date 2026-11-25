import type { AgentPerformance } from '@/lib/api/types';

/** Sorting and ranking for the performance table (ADR-030). */

export type SortKey =
  | 'resolved'
  | 'avgHandleSeconds'
  | 'medianHandleSeconds'
  | 'medianResolutionSeconds'
  | 'slaMetPercent'
  | 'reopenPercent'
  | 'openNow';

/** Whether a bigger number is better, which also sets the first sort direction. */
export const HIGHER_IS_BETTER: Record<SortKey, boolean> = {
  resolved: true,
  avgHandleSeconds: false,
  medianHandleSeconds: false,
  medianResolutionSeconds: false,
  slaMetPercent: true,
  reopenPercent: false,
  openNow: false,
};

function value(a: AgentPerformance, key: SortKey): number | null {
  return key === 'openNow' ? a.openNow : a.metrics[key];
}

/** Sorts by `key`; agents without a number always go last, then by name. */
export function sortAgents(
  agents: AgentPerformance[],
  key: SortKey,
  ascending: boolean,
): AgentPerformance[] {
  return [...agents].sort((x, y) => {
    const a = value(x, key);
    const b = value(y, key);
    if (a === null && b === null) return x.fullName.localeCompare(y.fullName);
    if (a === null) return 1;
    if (b === null) return -1;
    if (a !== b) return ascending ? a - b : b - a;
    return x.fullName.localeCompare(y.fullName);
  });
}

/** The best ranked agent for a metric, or null when nobody has enough resolutions. */
export function best(
  agents: AgentPerformance[],
  key: Exclude<SortKey, 'openNow'>,
): AgentPerformance | null {
  const ranked = agents.filter((a) => a.ranked && a.metrics[key] !== null);
  return sortAgents(ranked, key, !HIGHER_IS_BETTER[key])[0] ?? null;
}
