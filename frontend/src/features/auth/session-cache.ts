import { useEffect } from 'react';
import type { QueryClient } from '@tanstack/react-query';
import { useSession } from './session-store';

/**
 * Empties the server-data cache whenever the signed-in person changes (sign-out, or another user
 * signs in on the same browser). Without this the next user briefly saw the previous user's
 * tickets and notification count. A token refresh for the same person keeps the cache.
 */
export function useClearCacheOnUserChange(client: QueryClient): void {
  useEffect(
    () =>
      useSession.subscribe((state, previous) => {
        if (state.agent?.id !== previous.agent?.id) client.clear();
      }),
    [client],
  );
}
