import { api } from '@/lib/api/client';
import type { TokenResponse } from '@/lib/api/types';
import { useSession } from './session-store';

export async function login(email: string, password: string): Promise<void> {
  const { data } = await api.post<TokenResponse>('/auth/login', { email, password });
  useSession.getState().setSession(data.accessToken, data.agent);
}

export async function logout(): Promise<void> {
  try {
    await api.post('/auth/logout');
  } finally {
    useSession.getState().clear();
  }
}
