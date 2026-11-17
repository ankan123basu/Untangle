import { describe, expect, it } from 'vitest';
import { activeQuickView, parseQuery, toSearchParams } from './filters';
import { actionForMove, inputFor, slaState, TRANSITIONS } from './ticket-meta';

describe('URL filters', () => {
  it('round-trips every filter through the URL', () => {
    const url = new URLSearchParams(
      'status=OPEN&status=IN_PROGRESS&priority=CRITICAL&category=REFUND&mine=true&escalated=true&slaBreached=true&q=card&sort=priority%2Cdesc&page=2&size=50',
    );
    const query = parseQuery(url);
    expect(query).toEqual({
      status: ['OPEN', 'IN_PROGRESS'],
      priority: ['CRITICAL'],
      category: ['REFUND'],
      mine: true,
      escalated: true,
      slaBreached: true,
      q: 'card',
      sort: 'priority,desc',
      page: 2,
      size: 50,
    });
    expect(parseQuery(toSearchParams(query))).toEqual(query);
  });

  it('drops values the API would reject instead of sending them', () => {
    const query = parseQuery(
      new URLSearchParams('status=WHATEVER&priority=URGENT&sort=hack&size=1000&page=-3'),
    );
    expect(query).toEqual({ sort: 'createdAt,desc', page: 0, size: 20 });
  });

  it('keeps default values out of the URL', () => {
    expect(toSearchParams({ sort: 'createdAt,desc', page: 0, size: 20 }).toString()).toBe('');
  });

  it('recognises quick views regardless of parameter order or paging', () => {
    expect(activeQuickView(new URLSearchParams('priority=CRITICAL&unassigned=true&page=1'))).toBe(
      'unassigned-critical',
    );
    expect(activeQuickView(new URLSearchParams(''))).toBe('all');
    expect(activeQuickView(new URLSearchParams('priority=LOW'))).toBeUndefined();
  });
});

describe('ticket action metadata', () => {
  it('maps board moves to the action the server must allow', () => {
    expect(actionForMove('OPEN', 'IN_PROGRESS')).toBe('START_PROGRESS');
    expect(actionForMove('IN_PROGRESS', 'RESOLVED')).toBe('RESOLVE');
    expect(actionForMove('CLOSED', 'OPEN')).toBe('REOPEN');
    expect(actionForMove('OPEN', 'RESOLVED')).toBeUndefined(); // not a legal move at all
  });

  it('asks for a reason only when closing an OPEN ticket', () => {
    const close = TRANSITIONS.CLOSE;
    expect(close).toBeDefined();
    if (!close) return;
    expect(inputFor(close, 'OPEN')).toBe('reason');
    expect(inputFor(close, 'RESOLVED')).toBe('none');
  });
});

describe('SLA state', () => {
  const now = Date.parse('2026-11-18T12:00:00Z');
  const base = {
    status: 'OPEN' as const,
    slaBreached: false,
    slaStartedAt: '2026-11-18T08:00:00Z',
    now,
  };

  it('is on track with plenty of time left', () => {
    expect(slaState({ ...base, slaDueAt: '2026-11-18T16:00:00Z' })).toBe('ok');
  });
  it('is at risk with under a quarter of the target left', () => {
    expect(slaState({ ...base, slaDueAt: '2026-11-18T12:30:00Z' })).toBe('risk');
  });
  it('is breached past the deadline even before the scheduler flags it', () => {
    expect(slaState({ ...base, slaDueAt: '2026-11-18T11:59:00Z' })).toBe('breached');
  });
  it('stops counting once resolved', () => {
    expect(slaState({ ...base, status: 'RESOLVED', slaDueAt: '2026-11-18T11:00:00Z' })).toBe(
      'stopped',
    );
  });
});
