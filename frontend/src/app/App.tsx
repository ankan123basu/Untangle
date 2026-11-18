import { useState } from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider } from 'react-router/dom';
import { Toaster } from 'sonner';
import { AuthBootstrap } from '@/features/auth/guards';
import { useClearCacheOnUserChange } from '@/features/auth/session-cache';
import { createQueryClient } from '@/lib/query-client';
import { router } from './router';

/** Root providers: server-state cache, toasts, session bootstrap, router. */
export function App() {
  const [queryClient] = useState(createQueryClient);
  useClearCacheOnUserChange(queryClient);
  return (
    <QueryClientProvider client={queryClient}>
      <AuthBootstrap>
        <RouterProvider router={router} />
      </AuthBootstrap>
      <Toaster
        position="bottom-right"
        toastOptions={{
          className:
            'rounded-card! border! border-glass-edge! bg-glass-strong! backdrop-blur-xl! text-ink! shadow-soft! font-sans!',
        }}
      />
    </QueryClientProvider>
  );
}
