import type { ReactElement } from 'react';
import { render } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, type RouteObject } from 'react-router';
import { RouterProvider } from 'react-router/dom';

/** Renders routes inside a fresh query cache and an in-memory router (no real URL bar). */
export function renderRoutes(routes: RouteObject[], initialEntries: string[] = ['/']) {
  const router = createMemoryRouter(routes, { initialEntries });
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const result = render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return { router, client, ...result };
}

export function renderOne(element: ReactElement, path = '/') {
  return renderRoutes([{ path, element }], [path]);
}
