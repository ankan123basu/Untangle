import { createHmac, randomUUID } from 'node:crypto';
import { expect, type APIRequestContext, type Page } from '@playwright/test';

/** Demo seed accounts (db/seed-demo); every one uses the same demo password. */
export const DEMO_PASSWORD = 'Untangle@2026';
export const USERS = {
  admin: 'admin@untangle.local',
  lead: 'leela.thomas@untangle.local',
  agent: 'riya.sen@untangle.local',
} as const;

export function uniq(): string {
  return randomUUID().slice(0, 8);
}

/** Signs in through the real login form and waits for the app shell. */
export async function signIn(page: Page, email: string): Promise<void> {
  await page.goto('/login');
  await page.getByLabel(/work email/i).fill(email);
  await page.getByLabel(/^password/i).fill(DEMO_PASSWORD);
  await page.getByRole('button', { name: /sign in/i }).click();
  await expect(page.getByRole('navigation', { name: 'Main' })).toBeVisible();
}

export async function signOut(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page).toHaveURL(/\/login$/);
}

/** Access token for API-level setup (no browser). */
export async function apiToken(request: APIRequestContext, email: string): Promise<string> {
  const response = await request.post('/api/v1/auth/login', {
    data: { email, password: DEMO_PASSWORD },
  });
  expect(response.status()).toBe(200);
  const body = (await response.json()) as { accessToken: string };
  return body.accessToken;
}

/** The partner signature from docs/webhook-integration-guide.md: HMAC-SHA256 over "ts.body". */
export function signWebhook(secret: string, timestamp: string, rawBody: string): string {
  return (
    'sha256=' + createHmac('sha256', secret).update(`${timestamp}.`).update(rawBody).digest('hex')
  );
}
