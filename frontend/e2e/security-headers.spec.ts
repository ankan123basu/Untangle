import { expect, test } from '@playwright/test';

/** §6.3 security headers and safe error responses, checked on what the browser really receives. */
test.describe('security headers', () => {
  test('the app is served with a strict CSP and anti-framing headers', async ({ request }) => {
    const response = await request.get('/');
    expect(response.status()).toBe(200);
    const headers = response.headers();
    const csp = headers['content-security-policy'] ?? '';
    expect(csp).toContain("default-src 'self'");
    expect(csp).toContain("script-src 'self'");
    expect(csp).not.toContain("script-src 'self' 'unsafe-inline'");
    expect(csp).toContain("frame-ancestors 'none'");
    expect(csp).toContain("object-src 'none'");
    expect(headers['x-frame-options']).toBe('DENY');
    expect(headers['x-content-type-options']).toBe('nosniff');
    expect(headers['referrer-policy']).toBe('strict-origin-when-cross-origin');
    expect(headers['permissions-policy']).toContain('camera=()');
  });

  test('hashed assets are cached forever, the HTML never', async ({ request, page }) => {
    const html = await request.get('/');
    expect(html.headers()['cache-control']).toBe('no-cache');

    await page.goto('/login');
    const script = await page
      .locator('script[type="module"][src^="/assets/"]')
      .first()
      .getAttribute('src');
    expect(script).toBeTruthy();
    const asset = await request.get(script ?? '');
    expect(asset.headers()['cache-control']).toContain('immutable');
  });

  test('API errors are Problem Details without internals', async ({ request }) => {
    const response = await request.get('/api/v1/tickets');
    expect(response.status()).toBe(401);
    expect(response.headers()['content-type']).toContain('application/problem+json');
    const text = await response.text();
    expect(text).toContain('"traceId"');
    expect(text).not.toMatch(/Exception|\bat [a-z]+\.[a-z]+\.|SQL/);
  });

  test('metrics are not reachable through the public entry point', async ({ request }) => {
    const response = await request.get('/actuator/prometheus');
    expect(await response.text()).not.toContain('jvm_memory');
  });
});
