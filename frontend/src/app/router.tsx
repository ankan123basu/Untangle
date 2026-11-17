import { createBrowserRouter } from 'react-router';
import { AppShell } from '@/components/layout/app-shell';
import { RequireAuth, RequirePermission } from '@/features/auth/guards';
import { ForbiddenPage, NotFoundPage, RouteErrorPage } from './error-pages';

/**
 * Data router with lazy routes (§12.6): each screen is its own JS chunk, loaded when first visited,
 * so the login page doesn't download the admin screens (or, later, three.js).
 */
export const router = createBrowserRouter([
  {
    path: '/login',
    lazy: () => import('@/features/auth/login-page').then((m) => ({ Component: m.default })),
    errorElement: <RouteErrorPage />,
  },
  {
    path: '/',
    element: (
      <RequireAuth>
        <AppShell />
      </RequireAuth>
    ),
    errorElement: <RouteErrorPage />,
    children: [
      {
        index: true,
        lazy: () =>
          import('@/features/dashboard/dashboard-page').then((m) => ({ Component: m.default })),
      },
      {
        path: 'tickets',
        lazy: () =>
          import('@/features/tickets/tickets-page').then((m) => ({ Component: m.default })),
      },
      {
        path: 'tickets/new',
        lazy: () =>
          import('@/features/tickets/create-ticket-page').then((m) => ({ Component: m.default })),
      },
      {
        path: 'tickets/by-number/:number',
        lazy: () =>
          import('@/features/tickets/ticket-by-number').then((m) => ({ Component: m.default })),
      },
      {
        path: 'tickets/:id',
        lazy: () =>
          import('@/features/tickets/ticket-detail-page').then((m) => ({ Component: m.default })),
      },
      {
        path: 'admin/agents',
        lazy: () =>
          import('@/features/admin/agents-page').then((m) => ({
            Component: () => (
              <RequirePermission permission="AGENT_MANAGE">
                <m.default />
              </RequirePermission>
            ),
          })),
      },
      {
        path: 'admin/partners',
        lazy: () =>
          import('@/features/admin/partners-page').then((m) => ({
            Component: () => (
              <RequirePermission permission="PARTNER_MANAGE">
                <m.default />
              </RequirePermission>
            ),
          })),
      },
      {
        path: 'admin/webhooks',
        lazy: () =>
          import('@/features/admin/webhook-logs-page').then((m) => ({
            Component: () => (
              <RequirePermission permission="WEBHOOK_LOG_VIEW">
                <m.default />
              </RequirePermission>
            ),
          })),
      },
      { path: '403', element: <ForbiddenPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
]);
