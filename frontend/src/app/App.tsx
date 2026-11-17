import { LogoMark } from '@/components/brand/LogoMark';

/**
 * Phase 0 placeholder. It proves the toolchain works end to end (React, TypeScript, Tailwind
 * tokens, the build and the nginx container). The router, providers and real screens arrive in
 * Phases 7 and 8.
 */
export function App() {
  return (
    <main className="grid min-h-dvh place-items-center bg-paper p-6 text-ink">
      <section
        aria-labelledby="brand-title"
        className="w-full max-w-md rounded-card border-3 border-ink bg-surface p-8 shadow-hard"
      >
        <div className="flex items-center gap-3">
          <LogoMark size={48} title="" />
          <h1 id="brand-title" className="font-display text-4xl font-bold tracking-tight">
            Untangle
          </h1>
        </div>
        <p className="mt-4 text-lg">Every complaint, untangled.</p>
      </section>
    </main>
  );
}
