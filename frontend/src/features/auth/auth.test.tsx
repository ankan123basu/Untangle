import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { leadAgent, problem, server, tokenResponse } from '@/test/server';
import { renderRoutes } from '@/test/render';
import { RequireAuth, RequirePermission } from './guards';
import LoginPage from './login-page';
import { useSession } from './session-store';

const routes = [
  { path: '/login', element: <LoginPage /> },
  {
    path: '/',
    element: (
      <RequireAuth>
        <h1>Dashboard</h1>
      </RequireAuth>
    ),
  },
  {
    path: '/admin',
    element: (
      <RequireAuth>
        <RequirePermission permission="AGENT_MANAGE">
          <h1>Admin</h1>
        </RequirePermission>
      </RequireAuth>
    ),
  },
  { path: '/403', element: <h1>Forbidden</h1> },
];

async function fillAndSubmit(email: string, password: string) {
  const user = userEvent.setup();
  await user.type(screen.getByLabelText(/work email/i), email);
  await user.type(screen.getByLabelText(/^password/i), password);
  await user.click(screen.getByRole('button', { name: /sign in/i }));
}

describe('login', () => {
  it('validates the form before calling the API', async () => {
    useSession.getState().clear();
    renderRoutes(routes, ['/login']);

    await userEvent.setup().click(screen.getByRole('button', { name: /sign in/i }));

    expect(await screen.findByText('Enter your work email')).toBeInTheDocument();
    expect(screen.getByLabelText(/work email/i)).toHaveAttribute('aria-invalid', 'true');
  });

  it('signs in and returns to the page the user wanted', async () => {
    useSession.getState().clear();
    server.use(http.post('/api/v1/auth/login', () => HttpResponse.json(tokenResponse())));
    renderRoutes(routes, ['/login']);

    await fillAndSubmit('leela.thomas@untangle.local', 'Untangle@2026');

    expect(await screen.findByRole('heading', { name: 'Dashboard' })).toBeInTheDocument();
    expect(useSession.getState().agent?.fullName).toBe(leadAgent.fullName);
  });

  it('explains wrong credentials', async () => {
    useSession.getState().clear();
    server.use(
      http.post('/api/v1/auth/login', () =>
        problem(401, 'AUTH_INVALID_CREDENTIALS', 'Invalid email or password'),
      ),
    );
    renderRoutes(routes, ['/login']);

    await fillAndSubmit('leela.thomas@untangle.local', 'wrong-password');

    expect(await screen.findByRole('alert')).toHaveTextContent(/don’t match/);
  });

  it('shows when a locked account unlocks', async () => {
    useSession.getState().clear();
    server.use(
      http.post('/api/v1/auth/login', () =>
        problem(423, 'AUTH_ACCOUNT_LOCKED', 'Too many failed attempts', {
          lockedUntil: '2026-11-18T10:15:00Z',
        }),
      ),
    );
    renderRoutes(routes, ['/login']);

    await fillAndSubmit('leela.thomas@untangle.local', 'wrong-password');

    expect(await screen.findByRole('alert')).toHaveTextContent(/locked.*Try again after/s);
  });
});

describe('demo people', () => {
  it('lists every demo person and fills the form with the picked one', async () => {
    const user = userEvent.setup();
    renderRoutes(routes, ['/login']);

    await user.click(await screen.findByText('All demo people (9)'));
    await user.click(screen.getByRole('button', { name: /Tanvi Shah/ }));

    expect(screen.getByLabelText(/work email/i)).toHaveValue('tanvi.shah@untangle.local');
    expect(screen.getByLabelText(/^password/i)).toHaveValue('Untangle@2026');
  });
});

describe('login desk', () => {
  it('shows the static support desk, not WebGL, when 3D cannot run', async () => {
    // jsdom has no WebGL, so canRunScene3d says no: the stamped paper ticket stands in.
    const { container } = renderRoutes(routes, ['/login']);
    expect(await screen.findByText('UNT-000123')).toBeInTheDocument();
    expect(screen.getByText('RESOLVED')).toBeInTheDocument();
    expect(container.querySelector('canvas')).toBeNull();
  });
});

describe('route guards', () => {
  it('sends anonymous visitors to the login page', async () => {
    useSession.getState().clear();
    renderRoutes(routes, ['/']);
    expect(await screen.findByRole('button', { name: /sign in/i })).toBeInTheDocument();
  });

  it('sends users without the permission to 403', async () => {
    useSession.getState().setSession('t', leadAgent); // leads can't manage agents
    renderRoutes(routes, ['/admin']);
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Forbidden' })).toBeInTheDocument();
    });
  });
});
