import { renderHook } from '@testing-library/react';
import { QueryClient } from '@tanstack/react-query';
import { describe, expect, it } from 'vitest';
import { agentUser } from '@/test/fixtures';
import { leadAgent } from '@/test/server';
import { useClearCacheOnUserChange } from './session-cache';
import { useSession } from './session-store';

describe('session cache isolation', () => {
  it('drops cached data when the user signs out or someone else signs in', () => {
    const client = new QueryClient();
    useSession.getState().setSession('t1', leadAgent);
    renderHook(() => {
      useClearCacheOnUserChange(client);
    });

    client.setQueryData(['notifications', 'unread'], 32);
    useSession.getState().setSession('t2', leadAgent); // token refresh, same person
    expect(client.getQueryData(['notifications', 'unread'])).toBe(32);

    useSession.getState().clear(); // sign out
    expect(client.getQueryData(['notifications', 'unread'])).toBeUndefined();

    client.setQueryData(['notifications', 'unread'], 32);
    useSession.getState().setSession('t3', agentUser); // a different person signs in
    expect(client.getQueryData(['notifications', 'unread'])).toBeUndefined();
  });
});
