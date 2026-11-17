import type { ReactNode } from 'react';
import { isRouteErrorResponse, Link, useRouteError } from 'react-router';
import { LogoMark } from '@/components/brand/LogoMark';
import { ApiError } from '@/lib/api/problem';

function ErrorLayout({
  code,
  title,
  children,
}: {
  code: string;
  title: string;
  children: ReactNode;
}) {
  return (
    <main className="grid min-h-[70dvh] place-items-center p-6">
      <div className="solid-panel w-full max-w-lg p-8 text-center">
        <LogoMark size={40} title="" />
        <p className="mt-4 font-mono text-6xl font-bold">{code}</p>
        <h1 className="mt-2 font-display text-2xl font-bold">{title}</h1>
        <div className="mt-3 text-muted">{children}</div>
        <Link
          to="/"
          className="mt-6 inline-flex h-10 items-center rounded-md border-2 border-line bg-brand px-4 font-semibold text-on-brand shadow-hard-sm"
        >
          Back to the dashboard
        </Link>
      </div>
    </main>
  );
}

export function NotFoundPage() {
  return (
    <ErrorLayout code="404" title="Nothing tangled here">
      That page doesn’t exist, or it moved.
    </ErrorLayout>
  );
}

export function ForbiddenPage() {
  return (
    <ErrorLayout code="403" title="Not for your role">
      You don’t have permission to open this page. Ask an admin if you think you should.
    </ErrorLayout>
  );
}

/**
 * Route-level error boundary (§12.5 screen 10). Shows the server's traceId so support can find the
 * exact logs. Never shows stack traces.
 */
export function RouteErrorPage() {
  const error = useRouteError();
  if (isRouteErrorResponse(error) && error.status === 404) return <NotFoundPage />;
  if (error instanceof ApiError && error.status === 404) return <NotFoundPage />;
  if (error instanceof ApiError && error.status === 403) return <ForbiddenPage />;
  const traceId = error instanceof ApiError ? error.traceId : undefined;
  return (
    <ErrorLayout code="500" title="Something went wrong">
      <p>An unexpected error stopped this page. Reloading usually helps.</p>
      {traceId && (
        <p className="mt-2 font-mono text-xs">
          Reference: <span className="select-all">{traceId}</span>
        </p>
      )}
    </ErrorLayout>
  );
}
