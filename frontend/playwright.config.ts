import { defineConfig, devices } from '@playwright/test';

/**
 * End-to-end tests (Phase 10) against the running Docker stack (`make up`, demo profile): the real
 * nginx, backend, database and seed data, so they prove the pieces work together.
 * Base URL can be overridden with E2E_BASE_URL.
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false, // the specs share seeded demo accounts; keep them predictable
  workers: 1,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : [['list']],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:3000',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    viewport: { width: 1440, height: 900 },
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } },
    },
  ],
});
