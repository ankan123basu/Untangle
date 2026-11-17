import { create } from 'zustand';
import type { CurrentAgent, Permission } from '@/lib/api/types';

export type SessionStatus = 'loading' | 'authenticated' | 'anonymous';

interface SessionState {
  status: SessionStatus;
  /** The short-lived JWT. Memory only: never localStorage, so injected scripts can't steal it from storage. */
  accessToken: string | null;
  agent: CurrentAgent | null;
  setSession: (accessToken: string, agent: CurrentAgent) => void;
  clear: () => void;
}

/**
 * The signed-in user (§6.1). A page reload empties this store; the app then silently restores the
 * session from the HttpOnly refresh cookie (see AuthBootstrap).
 */
export const useSession = create<SessionState>((set) => ({
  status: 'loading',
  accessToken: null,
  agent: null,
  setSession: (accessToken, agent) => {
    set({ status: 'authenticated', accessToken, agent });
  },
  clear: () => {
    set({ status: 'anonymous', accessToken: null, agent: null });
  },
}));

/** True if the signed-in user has the permission. UI hints only; the server enforces. */
export function useCan(permission: Permission): boolean {
  return useSession((s) => s.agent?.permissions.includes(permission) ?? false);
}
