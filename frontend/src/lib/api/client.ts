import axios, { type AxiosError, type InternalAxiosRequestConfig } from 'axios';
import { useSession } from '@/features/auth/session-store';
import type { TokenResponse } from './types';
import { toApiError } from './problem';

/**
 * The ONE HTTP client (§12.6). Same origin: nginx (Docker) or Vite (dev) proxies /api, so the
 * HttpOnly refresh cookie flows without CORS.
 *
 * Interceptors:
 *  - request: attach the in-memory access token and a fresh X-Request-Id (traceable in server logs).
 *  - response: on 401, refresh the session ONCE (shared by all concurrent failures, "single-flight")
 *    and retry the original request. If refreshing fails, the user is signed out.
 *  - every error is normalised to ApiError (Problem Details).
 */
export const api = axios.create({
  baseURL: '/api/v1',
  headers: { 'Content-Type': 'application/json' },
  withCredentials: true,
  timeout: 20_000,
});

api.interceptors.request.use((config) => {
  const token = useSession.getState().accessToken;
  if (token) config.headers.Authorization = `Bearer ${token}`;
  config.headers['X-Request-Id'] = crypto.randomUUID();
  return config;
});

let refreshInFlight: Promise<string> | null = null;

/**
 * Exchanges the refresh cookie for a new access token. Concurrent callers share one request:
 * if five queries fail with 401 at once, only one refresh happens, and the rotated cookie isn't
 * reused (which the server would treat as theft, ADR-013).
 */
export function refreshSession(): Promise<string> {
  refreshInFlight ??= axios
    .post<TokenResponse>('/api/v1/auth/refresh', null, { withCredentials: true })
    .then(({ data }) => {
      useSession.getState().setSession(data.accessToken, data.agent);
      return data.accessToken;
    })
    .catch((error: unknown) => {
      useSession.getState().clear();
      throw toApiError(error);
    })
    .finally(() => {
      refreshInFlight = null;
    });
  return refreshInFlight;
}

interface RetriableConfig extends InternalAxiosRequestConfig {
  _retried?: boolean;
}

api.interceptors.response.use(undefined, async (error: AxiosError) => {
  const config = error.config as RetriableConfig | undefined;
  const isAuthCall = config?.url?.startsWith('/auth/') ?? false;
  if (error.response?.status === 401 && config && !config._retried && !isAuthCall) {
    config._retried = true;
    try {
      const token = await refreshSession();
      config.headers.Authorization = `Bearer ${token}`;
      return await api.request(config);
    } catch (refreshError) {
      throw toApiError(refreshError);
    }
  }
  throw toApiError(error);
});
