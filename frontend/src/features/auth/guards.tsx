import { useEffect, type ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router';
import { refreshSession } from '@/lib/api/client';
import type { Permission } from '@/lib/api/types';
import { BrandSplash } from '@/components/layout/brand-splash';
import { useSession } from './session-store';

/**
 * On first load the access token is gone (memory only), but the HttpOnly refresh cookie may still
 * be valid. Try one silent refresh before deciding whether the user is signed in.
 */
export function AuthBootstrap({ children }: { children: ReactNode }) {
  const status = useSession((s) => s.status);
  useEffect(() => {
    if (status === 'loading') {
      refreshSession().catch(() => {
        /* not signed in: the session store is now 'anonymous' */
      });
    }
  }, [status]);
  if (status === 'loading') return <BrandSplash />;
  return children;
}

/** Routes behind sign-in. Remembers where you were going, so login can send you back. */
export function RequireAuth({ children }: { children: ReactNode }) {
  const status = useSession((s) => s.status);
  const location = useLocation();
  if (status !== 'authenticated') {
    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
  }
  return children;
}

/**
 * Routes that need a permission (e.g. AGENT_MANAGE). This only hides UI the user can't use; the
 * backend enforces the same rule on every request.
 */
export function RequirePermission({
  permission,
  children,
}: {
  permission: Permission;
  children: ReactNode;
}) {
  const allowed = useSession((s) => s.agent?.permissions.includes(permission) ?? false);
  if (!allowed) return <Navigate to="/403" replace />;
  return children;
}
