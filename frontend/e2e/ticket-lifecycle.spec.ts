import { expect, test } from '@playwright/test';
import { signIn, signOut, uniq, USERS } from './support';

/**
 * The core journey (§14 Phase 10): an agent logs a complaint, works it to resolved; a lead closes
 * it, then reopens it when the customer comes back. Every step goes through the real UI and API.
 */
test('a complaint goes from intake to resolved, closed and reopened', async ({ page }) => {
  const subject = `E2E card declined abroad ${uniq()}`;

  // ---- Agent: create, assigned to self -------------------------------------------------------
  await signIn(page, USERS.agent);
  await page.goto('/tickets/new');
  await page.getByLabel(/^subject/i).fill(subject);
  await page.getByLabel(/^description/i).fill('Card is declined at every shop while travelling.');
  await page.getByLabel(/^customer name/i).fill('Meera Iyer');
  await page.getByLabel(/^priority/i).selectOption('HIGH');
  await page.getByRole('checkbox', { name: 'Assign to me' }).check();
  await page.getByRole('button', { name: 'Create ticket' }).click();

  await expect(page.getByRole('heading', { level: 1, name: subject })).toBeVisible();
  const ticketNumber =
    (await page
      .getByText(/^UNT-\d{6}$/)
      .first()
      .textContent()) ?? '';
  expect(ticketNumber).toMatch(/^UNT-\d{6}$/);
  const ticketUrl = page.url();
  const actions = page.getByRole('toolbar', { name: 'Ticket actions' });

  // ---- Agent: start, comment, resolve -----------------------------------------------------------
  await actions.getByRole('button', { name: 'Start progress' }).click();
  await expect(page.getByText(`${ticketNumber} → In progress`)).toBeVisible();

  await page
    .getByRole('textbox', { name: 'Comment' })
    .fill('Called the bank: overseas block lifted.');
  await page.keyboard.press('Control+Enter');
  await expect(
    page.getByRole('list', { name: 'Timeline' }).getByText('overseas block lifted'),
  ).toBeVisible();

  await actions.getByRole('button', { name: 'Resolve' }).click();
  const resolve = page.getByRole('dialog', { name: 'Resolve' });
  await resolve.getByLabel(/resolution note/i).fill('Bank removed the block; customer confirmed.');
  await resolve.getByRole('button', { name: 'Resolve' }).click();
  await expect(page.getByText(`${ticketNumber} → Resolved`)).toBeVisible();

  // Agents can't close (only leads/admins, by default): shown disabled with the reason.
  const close = actions.getByRole('button', { name: 'Close' });
  await expect(close).toHaveAttribute('aria-disabled', 'true');
  await expect(close).toHaveAccessibleDescription(/role can’t do this/i);
  await signOut(page);

  // ---- Lead: close, then reopen -----------------------------------------------------------------
  await signIn(page, USERS.lead);
  await page.goto(ticketUrl);
  await page
    .getByRole('toolbar', { name: 'Ticket actions' })
    .getByRole('button', { name: 'Close' })
    .click();
  await expect(page.getByText('This ticket is closed and read-only.')).toBeVisible();
  await expect(page.getByRole('textbox', { name: 'Comment' })).toHaveCount(0);

  await page.getByRole('button', { name: 'Reopen' }).click();
  const reopen = page.getByRole('dialog', { name: `Reopen ${ticketNumber}` });
  await reopen.getByLabel(/reason/i).fill('Customer says the card is declined again.');
  await reopen.getByRole('button', { name: 'Reopen' }).click();

  await expect(page.getByText('This ticket is closed and read-only.')).toHaveCount(0);
  await expect(
    page.getByRole('list', { name: 'Timeline' }).getByText('Ticket reopened'),
  ).toBeVisible();
});
