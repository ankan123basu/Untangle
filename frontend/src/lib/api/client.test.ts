import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { useSession } from '@/features/auth/session-store';
import { leadAgent, problem, server, tokenResponse } from '@/test/server';
import { api } from './client';
import { ApiError, messageFor, toApiError } from './problem';

describe('api client', () => {
  it('sends the in-memory access token and a request id', async () => {
    useSession.getState().setSession('abc', leadAgent);
    let seen: Headers | undefined;
    server.use(
      http.get('/api/v1/ping', ({ request }) => {
        seen = request.headers;
        return HttpResponse.json({ ok: true });
      }),
    );

    await api.get('/ping');

    expect(seen?.get('authorization')).toBe('Bearer abc');
    expect(seen?.get('x-request-id')).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('refreshes ONCE for concurrent 401s and retries every request with the new token', async () => {
    useSession.getState().setSession('expired', leadAgent);
    let refreshCalls = 0;
    server.use(
      http.post('/api/v1/auth/refresh', () => {
        refreshCalls++;
        return HttpResponse.json(tokenResponse(leadAgent, 'fresh'));
      }),
      http.get('/api/v1/things/:n', ({ request, params }) =>
        request.headers.get('authorization') === 'Bearer fresh'
          ? HttpResponse.json({ n: params.n })
          : problem(401, 'AUTH_TOKEN_EXPIRED', 'expired'),
      ),
    );

    const results = await Promise.all(
      [1, 2, 3].map((n) => api.get<{ n: string }>(`/things/${String(n)}`)),
    );

    expect(refreshCalls).toBe(1);
    expect(results.map((r) => r.data.n)).toEqual(['1', '2', '3']);
    expect(useSession.getState().accessToken).toBe('fresh');
  });

  it('signs the user out when the refresh itself fails', async () => {
    useSession.getState().setSession('expired', leadAgent);
    server.use(
      http.get('/api/v1/things', () => problem(401, 'AUTH_TOKEN_EXPIRED', 'expired')),
      http.post('/api/v1/auth/refresh', () => problem(401, 'AUTH_REFRESH_REUSED', 'reused')),
    );

    await expect(api.get('/things')).rejects.toMatchObject({ code: 'AUTH_REFRESH_REUSED' });
    expect(useSession.getState().status).toBe('anonymous');
  });

  it('turns Problem Details into ApiError with code, field errors and traceId', async () => {
    server.use(
      http.post('/api/v1/tickets', () =>
        problem(400, 'VALIDATION_FAILED', 'Request has 1 invalid field', {
          errors: [{ field: 'priority', message: 'must not be null' }],
        }),
      ),
    );

    const error = await api.post('/tickets', {}).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    const apiError = error as ApiError;
    expect(apiError.code).toBe('VALIDATION_FAILED');
    expect(apiError.traceId).toBe('trace-123');
    expect(apiError.fieldErrors).toEqual([{ field: 'priority', message: 'must not be null' }]);
  });
});

describe('error messages', () => {
  it('maps known codes to friendly text and falls back to the server detail', () => {
    expect(messageFor(new ApiError({ status: 409, code: 'TICKET_CLOSED', message: 'x' }))).toMatch(
      /closed/i,
    );
    expect(
      messageFor(new ApiError({ status: 418, code: 'SOMETHING_NEW', message: 'Server says hi' })),
    ).toBe('Server says hi');
  });

  it('treats a missing response as a network error', () => {
    expect(toApiError(Object.assign(new Error('Network Error'), { isAxiosError: true })).code).toBe(
      'NETWORK_ERROR',
    );
  });
});
