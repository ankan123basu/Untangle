import { Component, lazy, memo, Suspense, useState, type ReactNode } from 'react';
import { Clock, Lock, TrendingUp } from 'lucide-react';
import { LogoMark } from '@/components/brand/LogoMark';
import { useScene3d } from '@/hooks/use-theme';
import { useShadowColor } from '@/components/three/support';
import { canRunHeroScene } from './hero-support';

// three.js stays out of the main bundle (ADR-024): this chunk loads only when the scene will run.
const DeskScene = lazy(() => import('./desk-scene'));

/** If WebGL fails at runtime (context lost, driver bug), quietly show the static desk instead. */
class SceneBoundary extends Component<
  { children: ReactNode; fallback: ReactNode },
  { failed: boolean }
> {
  override state = { failed: false };
  static getDerivedStateFromError() {
    return { failed: true };
  }
  override render() {
    return this.state.failed ? this.props.fallback : this.props.children;
  }
}

/**
 * The same desk without WebGL: a stamped ticket between the SLA clock, the escalation levels and
 * the HMAC lock. Shown with reduced motion, without WebGL, with 3D off, and while the chunk loads.
 */
function StaticDesk() {
  return (
    <div className="absolute inset-0 flex items-center justify-center gap-6 px-4">
      <span className="hidden size-16 rotate-[-8deg] items-center justify-center rounded-full border-[3px] border-[#111] bg-[#5cc8ff] text-[#111] shadow-[5px_5px_0_0_#111] xl:flex">
        <Clock className="size-8" strokeWidth={2.5} />
      </span>
      <div className="relative w-72 rotate-[-3deg] rounded-md border-[3px] border-[#111] bg-[#fffdf5] p-5 text-[#111] shadow-[8px_8px_0_0_#111]">
        <p className="font-mono text-sm font-bold">UNT-000123</p>
        <p className="mt-2 font-display text-2xl leading-tight font-bold">Card charged twice</p>
        <p className="mt-1 text-sm text-[#4d4840]">Assigned to Riya · SLA 1 h</p>
        <span className="mt-4 inline-block border-[3px] border-[#111] bg-[#c81e1e] px-2 py-0.5 text-xs font-extrabold text-white">
          CRITICAL
        </span>
        <span className="absolute right-4 bottom-4 rotate-[-12deg] border-[5px] border-[#16a34a] px-2 font-display text-xl font-black text-[#16a34a]">
          RESOLVED
        </span>
      </div>
      <span className="hidden flex-col gap-2 xl:flex">
        <span className="flex size-12 rotate-[6deg] items-center justify-center rounded-md border-[3px] border-[#111] bg-[#f59e0b] text-[#111] shadow-[4px_4px_0_0_#111]">
          <TrendingUp className="size-6" strokeWidth={2.5} />
        </span>
        <span className="flex size-12 rotate-[-6deg] items-center justify-center rounded-md border-[3px] border-[#111] bg-[#2a2a2a] text-[#ffd23f] shadow-[4px_4px_0_0_#111]">
          <Lock className="size-6" strokeWidth={2.5} />
        </span>
      </span>
    </div>
  );
}

const MARQUEE = [
  'SLA timers',
  'Auto-escalation L1 → L3',
  'HMAC-signed webhooks',
  'Role-based access',
  'Live notifications',
  'Every complaint, untangled',
];

/**
 * Left half of the login (lg and up): brand row, the 3D support desk in its own stage, the
 * headline, and a ticker band. Each has its own row, so nothing overlaps.
 */
export const LoginHero = memo(function LoginHero() {
  const [capable] = useState(canRunHeroScene);
  const [choice] = useScene3d();
  const shadow = useShadowColor();
  const show3d = capable && choice !== 'off';
  return (
    <section
      aria-hidden="true"
      className="relative hidden min-h-dvh flex-col overflow-hidden border-r-[3px] border-line bg-hero text-hero-ink lg:flex"
    >
      <div className="flex items-center justify-between gap-4 px-10 pt-9 xl:px-14">
        <div className="flex items-center gap-3">
          <span className="rounded-md border-[3px] border-line bg-surface p-1 shadow-hard-sm">
            <LogoMark size={34} title="" />
          </span>
          <span className="font-display text-2xl font-bold tracking-tight">Untangle</span>
        </div>
        <span className="animate-wiggle rounded-full border-[3px] border-line bg-surface px-3 py-1 text-xs font-extrabold tracking-wide text-ink uppercase shadow-hard-sm [--r:-4deg]">
          No more lost tickets
        </span>
      </div>

      <div className="relative min-h-[260px] flex-1">
        {show3d ? (
          <SceneBoundary fallback={<StaticDesk />}>
            <Suspense fallback={<StaticDesk />}>
              <DeskScene shadowColor={shadow} />
            </Suspense>
          </SceneBoundary>
        ) : (
          <StaticDesk />
        )}
      </div>

      <div className="px-10 pb-8 xl:px-14">
        <p className="font-display text-6xl leading-[0.92] font-bold tracking-tight xl:text-7xl">
          Every complaint,
          <br />
          <span className="inline-block rotate-[-1.5deg] rounded-md border-[3px] border-line bg-surface px-3 text-ink shadow-hard">
            untangled.
          </span>
        </p>
        <p className="mt-6 max-w-lg text-lg font-medium text-hero-muted">
          Every customer complaint, from your agents or your partners, gets an owner, a deadline and
          an escalation path, so nothing slips through.
        </p>
      </div>

      <div className="marquee border-t-[3px] border-line bg-ink py-3 text-paper">
        {[0, 1].map((copy) => (
          <div key={copy}>
            {MARQUEE.map((item) => (
              <span
                key={item}
                className="flex items-center gap-6 pr-6 font-display text-lg font-bold tracking-wide uppercase"
              >
                {item}
                <span className="text-brand">✦</span>
              </span>
            ))}
          </div>
        ))}
      </div>
    </section>
  );
});
