import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it } from 'vitest';
import { useSession } from '@/features/auth/session-store';
import type { AgentPerformance, PerformanceMetrics, PerformanceReport } from '@/lib/api/types';
import { renderRoutes } from '@/test/render';
import { leadAgent, server } from '@/test/server';
import { best, sortAgents } from './performance-model';
import PerformancePage from './performance-page';

function metrics(overrides: Partial<PerformanceMetrics> = {}): PerformanceMetrics {
  return {
    resolved: 5,
    avgHandleSeconds: 3600,
    medianHandleSeconds: 3000,
    medianResolutionSeconds: 7200,
    slaMetPercent: 80,
    reopenPercent: 0,
    ...overrides,
  };
}

function agent(
  id: string,
  fullName: string,
  m: Partial<PerformanceMetrics>,
  overrides: Partial<AgentPerformance> = {},
): AgentPerformance {
  const mm = metrics(m);
  return {
    agentId: id,
    fullName,
    role: 'AGENT',
    ai: false,
    active: true,
    openNow: 2,
    ranked: mm.resolved >= 3,
    metrics: mm,
    ...overrides,
  };
}

const zoya = agent('z', 'Zoya Ali', { resolved: 8, medianHandleSeconds: 1800, slaMetPercent: 90 });
const dev = agent('d', 'Dev Malhotra', {
  resolved: 4,
  medianHandleSeconds: 900,
  slaMetPercent: 50,
});
const newbie = agent('n', 'New Starter', {
  resolved: 1,
  medianHandleSeconds: 60,
  slaMetPercent: 100,
});
const idle = agent('i', 'Idle Person', {
  resolved: 0,
  avgHandleSeconds: null,
  medianHandleSeconds: null,
  medianResolutionSeconds: null,
  slaMetPercent: null,
  reopenPercent: null,
});

function report(days = 30): PerformanceReport {
  return {
    days,
    from: '2026-10-20T00:00:00Z',
    to: '2026-11-19T00:00:00Z',
    minResolvedToRank: 3,
    team: metrics({ resolved: 13 }),
    agents: [zoya, dev, newbie, idle],
  };
}

beforeEach(() => {
  useSession.getState().setSession('t', leadAgent);
});

describe('performance helpers', () => {
  it('ranks only agents with enough resolutions', () => {
    const all = [zoya, dev, newbie, idle];
    expect(best(all, 'medianHandleSeconds')?.fullName).toBe('Dev Malhotra');
    expect(best(all, 'slaMetPercent')?.fullName).toBe('Zoya Ali');
    expect(best([newbie, idle], 'resolved')).toBeNull();
  });

  it('keeps agents without numbers last in either direction', () => {
    const asc = sortAgents([idle, zoya, dev], 'medianHandleSeconds', true);
    const desc = sortAgents([idle, zoya, dev], 'medianHandleSeconds', false);
    expect(asc.map((a) => a.agentId)).toEqual(['d', 'z', 'i']);
    expect(desc.map((a) => a.agentId)).toEqual(['z', 'd', 'i']);
  });
});

describe('performance page', () => {
  it('shows team totals, top performers and sorts the table', async () => {
    const asked: string[] = [];
    server.use(
      http.get('/api/v1/dashboard/agent-performance', ({ request }) => {
        const days = new URL(request.url).searchParams.get('days') ?? '';
        asked.push(days);
        return HttpResponse.json(report(Number(days)));
      }),
    );
    renderRoutes([{ path: '/performance', element: <PerformancePage /> }], ['/performance']);

    const totals = await screen.findByRole('region', { name: 'Team totals' });
    expect(within(totals).getByText('13')).toBeInTheDocument();

    const top = screen.getByRole('region', { name: 'Top performers' });
    expect(within(top).getByText('Fastest').parentElement).toHaveTextContent('Dev Malhotra');
    expect(within(top).getByText('Most resolved').parentElement).toHaveTextContent('Zoya Ali');

    const table = screen.getByRole('table', { name: 'Performance per agent' });
    const names = () =>
      within(table)
        .getAllByRole('rowheader')
        .map((h) => h.querySelector('span')?.textContent);
    expect(names()).toEqual(['Zoya Ali', 'Dev Malhotra', 'New Starter', 'Idle Person']);

    await userEvent.click(within(table).getByRole('button', { name: 'Median handle' }));
    expect(names()).toEqual(['New Starter', 'Dev Malhotra', 'Zoya Ali', 'Idle Person']);
    expect(within(table).getByRole('columnheader', { name: 'Median handle' })).toHaveAttribute(
      'aria-sort',
      'ascending',
    );

    await userEvent.click(screen.getByRole('button', { name: '7 days' }));
    await waitFor(() => {
      expect(asked).toEqual(['30', '7']);
    });
  });

  it('explains when nobody has enough resolutions to rank', async () => {
    server.use(
      http.get('/api/v1/dashboard/agent-performance', () =>
        HttpResponse.json({ ...report(), agents: [newbie, idle] }),
      ),
    );
    renderRoutes([{ path: '/performance', element: <PerformancePage /> }], ['/performance']);
    expect(
      await screen.findByText(/Top performers appear once an agent has resolved at least 3/),
    ).toBeInTheDocument();
  });
});
