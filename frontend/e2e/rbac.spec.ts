import { expect, test } from '@playwright/test';
import { apiToken, signIn, USERS } from './support';

/** Roles in the UI (§6.2) and, more importantly, at the API (the real enforcement). */
test.describe('role-based access', () => {
  test('an agent sees no admin areas and gets the 403 page if they try', async ({ page }) => {
    await signIn(page, USERS.agent);
    const nav = page.getByRole('navigation', { name: 'Main' });
    await expect(nav.getByRole('link', { name: 'Tickets' })).toBeVisible();
    await expect(nav.getByRole('link', { name: 'Agents' })).toHaveCount(0);
    await expect(nav.getByRole('link', { name: 'Partners' })).toHaveCount(0);
    // Workload is a lead/admin view.
    await expect(page.getByRole('heading', { name: 'Agent workload' })).toHaveCount(0);

    await page.goto('/admin/agents');
    await expect(page.getByText('Not for your role')).toBeVisible();
  });

  test('a lead sees team workload but not agent management', async ({ page }) => {
    await signIn(page, USERS.lead);
    await expect(page.getByRole('heading', { name: 'Agent workload' })).toBeVisible();
    await expect(
      page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Agents' }),
    ).toHaveCount(0);
  });

  test('the API refuses what the UI hides', async ({ request }) => {
    const token = await apiToken(request, USERS.agent);
    const response = await request.get('/api/v1/admin/agents', {
      headers: { Authorization: `Bearer ${token}` },
    });
    expect(response.status()).toBe(403);
    expect(response.headers()['content-type']).toContain('application/problem+json');
    const problem = (await response.json()) as { code: string; traceId: string };
    expect(problem.code).toBe('ACCESS_DENIED');
    expect(problem.traceId).toBeTruthy();
  });
});
