/// <reference lib="dom" />
import { expect, test, type Page } from '@playwright/test';
import { signIn, USERS } from './support';

/*
 * Layout guard (ADR-027): every screen, at phone, tablet, laptop and desktop widths, for every
 * role. Fails when
 *   - the page scrolls sideways (something is wider than the screen), or
 *   - a visible control (link, button, input, select, textarea) is covered by something else at
 *     its centre (overlapping UI), or
 *   - a heading is cut off (its text is wider than its box).
 * Each problem is reported with the page, size and element, so a failure says what to fix.
 */

const SIZES = [
  { name: 'phone', width: 375, height: 812 },
  { name: 'tablet', width: 768, height: 1024 },
  { name: 'laptop', width: 1024, height: 768 },
  { name: 'desktop', width: 1440, height: 900 },
] as const;

type Problem = string;

/** Runs inside the page: finds sideways overflow, covered controls and clipped headings. */
async function audit(page: Page): Promise<Problem[]> {
  return page.evaluate(async () => {
    const problems: string[] = [];
    const vw = window.innerWidth;
    const vh = window.innerHeight;
    const doc = document.documentElement;
    if (doc.scrollWidth > vw + 1) {
      // Name the widest offenders so the report is actionable.
      const wide = [...document.querySelectorAll<HTMLElement>('body *')]
        .filter((el) => {
          const r = el.getBoundingClientRect();
          return r.width > 0 && r.right > vw + 1 && getComputedStyle(el).position !== 'fixed';
        })
        .slice(0, 3)
        .map((el) => describe(el));
      problems.push(
        `page is ${String(doc.scrollWidth)}px wide in a ${String(vw)}px screen: ${wide.join(' | ')}`,
      );
    }

    function describe(el: Element): string {
      const label = [el.getAttribute('aria-label'), el.textContent]
        .filter(Boolean)
        .join(' ')
        .trim()
        .replace(/\s+/g, ' ')
        .slice(0, 40);
      return `<${el.tagName.toLowerCase()}${el.id ? `#${el.id}` : ''}> "${label}"`;
    }

    function visible(el: Element): boolean {
      const style = getComputedStyle(el);
      if (style.visibility === 'hidden' || style.display === 'none' || Number(style.opacity) === 0)
        return false;
      // Visually hidden (sr-only / skip links until focused) and collapsed <details> content.
      const clip = style.getPropertyValue('clip');
      if (clip === 'rect(0px, 0px, 0px, 0px)' || style.clipPath === 'inset(50%)') return false;
      const closed = el.closest('details:not([open])');
      if (closed && !el.closest('summary')) return false;
      return !el.closest('[aria-hidden="true"], [inert], dialog:not([open])');
    }

    const seen = new Set<Element>();
    const step = Math.max(200, vh - 120);
    for (let y = 0; y <= Math.max(0, doc.scrollHeight - vh) + step - 1; y += step) {
      window.scrollTo(0, y);
      await new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)));
      const controls = document.querySelectorAll('a[href], button, input, select, textarea');
      for (const el of controls) {
        if (seen.has(el) || !visible(el)) continue;
        const r = el.getBoundingClientRect();
        if (r.width < 6 || r.height < 6) continue; // visually hidden inputs (sr-only)
        const cx = r.left + r.width / 2;
        const cy = r.top + r.height / 2;
        if (cx < 0 || cx > vw || cy < 0 || cy > vh) continue; // off screen (e.g. closed drawer)
        seen.add(el);
        const hit = document.elementFromPoint(cx, cy);
        if (!hit || el === hit || el.contains(hit) || hit.contains(el)) continue;
        // A label wrapping its own control, or a sticky header passing over content while
        // scrolling, is not an overlap bug.
        if (hit.closest('label')?.contains(el)) continue;
        if (hit.closest('header, nav, [data-sticky]') && !el.closest('header, nav')) continue;
        problems.push(`${describe(el)} is covered by ${describe(hit)}`);
      }
    }
    window.scrollTo(0, 0);

    // Floating controls (absolute/fixed, e.g. a corner icon) must not sit on a panel they don't
    // belong to. Pop-ups (dialogs, menus) overlay on purpose and are skipped.
    const panels = [...document.querySelectorAll('.solid-panel, .glass-panel')].filter(visible);
    for (const el of document.querySelectorAll('a[href], button, input, select')) {
      if (!visible(el) || el.closest('[role="dialog"], dialog, nav')) continue;
      const pos = getComputedStyle(
        el.closest('[class*="absolute"], [class*="fixed"]') ?? el,
      ).position;
      if (pos !== 'absolute' && pos !== 'fixed') continue;
      const r = el.getBoundingClientRect();
      if (r.width < 6 || r.height < 6) continue;
      for (const panel of panels) {
        if (panel.contains(el) || el.contains(panel)) continue;
        const p = panel.getBoundingClientRect();
        const w = Math.min(r.right, p.right) - Math.max(r.left, p.left);
        const h = Math.min(r.bottom, p.bottom) - Math.max(r.top, p.top);
        if (w > 2 && h > 2)
          problems.push(`${describe(el)} floats over panel ${describe(panel).slice(0, 60)}`);
      }
    }

    for (const h of document.querySelectorAll<HTMLElement>('h1, h2')) {
      if (!visible(h)) continue;
      const style = getComputedStyle(h);
      if (style.textOverflow === 'ellipsis') continue; // truncation by design
      if (h.scrollWidth > h.clientWidth + 2 && style.overflowX !== 'visible')
        problems.push(`heading ${describe(h)} is cut off`);
    }
    return problems;
  });
}

async function check(page: Page, where: string, problems: Problem[]) {
  for (const size of SIZES) {
    await page.setViewportSize({ width: size.width, height: size.height });
    // Let layout, lazy chunks and data settle.
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(400);
    for (const p of await audit(page)) problems.push(`[${where} @ ${size.name}] ${p}`);
  }
}

async function visit(page: Page, path: string, ready: () => Promise<void>) {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto(path);
  await ready();
}

test.describe('layout: nothing overlaps or spills sideways, at every size', () => {
  test.setTimeout(240_000);

  test('login', async ({ page }) => {
    const problems: Problem[] = [];
    await page.goto('/login');
    await expect(page.getByRole('heading', { name: /welcome back/i })).toBeVisible();
    await check(page, '/login', problems);
    expect(problems, problems.join('\n')).toEqual([]);
  });

  for (const role of ['admin', 'lead', 'agent'] as const) {
    test(`every screen as ${role}`, async ({ page }) => {
      const problems: Problem[] = [];
      await signIn(page, USERS[role]);
      const h1 = (name: RegExp) => async () => {
        await expect(page.getByRole('heading', { level: 1, name })).toBeVisible();
      };

      const screens: [string, RegExp][] = [
        ['/', /good (morning|afternoon|evening)/i],
        ['/tickets', /tickets/i],
        ['/board', /board/i],
        ['/tickets/new', /new ticket/i],
      ];
      if (role !== 'agent') {
        screens.push(['/auto-assign', /auto-assign/i]);
      }
      if (role === 'admin') {
        screens.push(['/admin/agents', /agents/i], ['/admin/partners', /partners/i]);
        screens.push(['/admin/webhooks', /webhook logs/i]);
      }
      for (const [path, name] of screens) {
        await visit(page, path, h1(name));
        await check(page, `${role} ${path}`, problems);
      }

      // A real ticket's detail page, opened from the list.
      await visit(page, '/tickets', h1(/tickets/i));
      const first = page.locator('main a[href^="/tickets/0"]:visible').first();
      if ((await first.count()) > 0) {
        await first.click();
        await expect(page.getByRole('link', { name: /all tickets/i })).toBeVisible();
        await check(page, `${role} ticket detail`, problems);
      }

      expect(problems, problems.join('\n')).toEqual([]);
    });
  }
});

/** Fails when the element is not fully inside the screen (with 1 px tolerance). */
async function expectOnScreen(page: Page, what: string, locator: ReturnType<Page['locator']>) {
  await expect(locator).toBeVisible();
  await page.waitForTimeout(450); // let slide/rise animations finish before measuring
  const box = await locator.boundingBox();
  const vp = page.viewportSize();
  expect(box, `${what} has no box`).not.toBeNull();
  if (!box || !vp) return;
  expect(box.x, `${what} sticks out on the left`).toBeGreaterThanOrEqual(-1);
  expect(box.x + box.width, `${what} sticks out on the right`).toBeLessThanOrEqual(vp.width + 1);
  expect(box.y, `${what} sticks out at the top`).toBeGreaterThanOrEqual(-1);
  expect(box.y + box.height, `${what} sticks out at the bottom`).toBeLessThanOrEqual(vp.height + 1);
  const wide = await page.evaluate(
    () => document.documentElement.scrollWidth > window.innerWidth + 1,
  );
  expect(wide, `${what} makes the page scroll sideways`).toBe(false);
}

test.describe('layout: pop-ups and drawers stay on a phone screen', () => {
  test('menu drawer, appearance, notifications, search and edit dialog at 375 px', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 375, height: 812 });
    await signIn(page, USERS.lead);

    await page.getByRole('button', { name: 'Open menu' }).click();
    await expectOnScreen(page, 'menu drawer', page.getByRole('navigation', { name: 'Main' }));
    await page.keyboard.press('Escape');

    await page.getByRole('button', { name: 'Appearance' }).click();
    await expectOnScreen(page, 'appearance menu', page.getByRole('dialog', { name: 'Appearance' }));
    await page.keyboard.press('Escape');

    await page.getByRole('button', { name: /^Notifications/ }).click();
    await expectOnScreen(
      page,
      'notifications',
      page.getByRole('dialog', { name: 'Notifications' }),
    );
    await page.keyboard.press('Escape');

    await page
      .getByRole('button', { name: /search/i })
      .first()
      .click();
    await expectOnScreen(
      page,
      'search palette',
      page.getByRole('dialog', { name: 'Command palette' }),
    );
    await page.keyboard.press('Escape');

    await page.goto('/tickets');
    // Phones show the list as cards, so pick the first visible ticket link wherever it is.
    await page.locator('main a[href^="/tickets/0"]:visible').first().click();
    const edit = page.getByRole('button', { name: 'Edit', exact: true });
    if ((await edit.count()) > 0) {
      await edit.click();
      await expectOnScreen(page, 'edit dialog', page.getByRole('dialog', { name: /^Edit UNT-/ }));
    }
  });
});
