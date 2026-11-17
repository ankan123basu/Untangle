import { expect, test } from '@playwright/test';
import { apiToken, signIn, signOut, signWebhook, uniq, USERS } from './support';

/**
 * A partner complaint arrives through the HMAC-signed webhook and shows up for the team, with an
 * audit row in the webhook logs (§7, §14 Phase 10).
 */
test('a signed partner webhook creates a ticket the team can see', async ({ page, request }) => {
  // ---- Admin (API): a fresh partner and its one-time secret -----------------------------------
  const admin = await apiToken(request, USERS.admin);
  const code = `e2e-${uniq()}`;
  const created = await request.post('/api/v1/admin/partners', {
    headers: { Authorization: `Bearer ${admin}` },
    data: { code, name: `E2E Bank ${code}`, rateLimitPerMinute: 60 },
  });
  expect(created.status()).toBe(201);
  const { partner, secret } = (await created.json()) as { partner: { id: string }; secret: string };

  // ---- Partner: send a signed complaint ---------------------------------------------------------
  const subject = `E2E partner refund ${uniq()}`;
  const body = JSON.stringify({
    customerName: 'Kabir Rao',
    subject,
    description: 'Refund for a cancelled order has not arrived after 10 days.',
    category: 'REFUND',
    priority: 'CRITICAL',
  });
  const timestamp = String(Math.floor(Date.now() / 1000));
  const sent = await request.post('/api/v1/webhooks/partners/tickets', {
    headers: {
      'Content-Type': 'application/json',
      'X-Partner-Id': code,
      'X-Event-Id': `evt_${uniq()}`,
      'X-Timestamp': timestamp,
      'X-Signature': signWebhook(secret, timestamp, body),
    },
    data: body,
  });
  expect(sent.status()).toBe(201);
  const { ticketNumber } = (await sent.json()) as { ticketNumber: string };

  // A tampered body is rejected with a generic 401 (no hint which check failed).
  const tampered = await request.post('/api/v1/webhooks/partners/tickets', {
    headers: {
      'Content-Type': 'application/json',
      'X-Partner-Id': code,
      'X-Event-Id': `evt_${uniq()}`,
      'X-Timestamp': timestamp,
      'X-Signature': signWebhook(secret, timestamp, body),
    },
    data: body.replace('CRITICAL', 'LOW'),
  });
  expect(tampered.status()).toBe(401);

  // ---- Lead (UI): the ticket is in the queue, marked as coming from a partner ------------------
  await signIn(page, USERS.lead);
  await page.goto(`/tickets?q=${encodeURIComponent(ticketNumber)}`);
  const row = page.getByRole('row').filter({ hasText: ticketNumber });
  await expect(row).toBeVisible();
  await expect(row.getByText(subject)).toBeVisible();
  await expect(row.getByText('Partner', { exact: true })).toBeVisible();
  await expect(row.getByText('Critical', { exact: true })).toBeVisible();

  // ---- Admin (UI): both attempts are in the delivery log ---------------------------------------
  await signOut(page);
  await signIn(page, USERS.admin);
  await page.goto(`/admin/webhooks?partnerId=${partner.id}`);
  await expect(page.getByRole('link', { name: ticketNumber })).toBeVisible();
  await expect(page.getByRole('table').getByText('Rejected signature')).toBeVisible();

  // Tidy up: disable the partner so reruns don't accumulate active partners.
  await request.patch(`/api/v1/admin/partners/${partner.id}/active`, {
    headers: { Authorization: `Bearer ${admin}` },
    data: { active: false },
  });
});
