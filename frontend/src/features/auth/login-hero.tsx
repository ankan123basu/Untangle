import { Component, lazy, Suspense, useState, type ReactNode } from 'react';
import { Clock, ShieldCheck, Webhook } from 'lucide-react';
import { LogoMark } from '@/components/brand/LogoMark';
import { canRunHeroScene } from './hero-support';

const HeroScene = lazy(() => import('./hero-scene'));

/** If WebGL fails at runtime (context lost, driver bug), quietly keep the static hero. */
class SceneBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  override state = { failed: false };
  static getDerivedStateFromError() {
    return { failed: true };
  }
  override render() {
    return this.state.failed ? null : this.props.children;
  }
}

/** Left half of the login screen: brand, headline, and (when possible) the 3D scene. */
export function LoginHero() {
  const [show3d] = useState(canRunHeroScene);
  return (
    <section
      aria-hidden="true"
      className="relative hidden flex-col justify-between overflow-hidden border-r-2 border-line bg-brand p-12 lg:flex"
    >
      {/* Static art: the fallback, and what shows while the 3D chunk loads. */}
      <div className="absolute -top-24 -right-24 size-96 rounded-full border-2 border-line bg-paper/40" />
      {show3d && (
        <SceneBoundary>
          {/* The scene owns the band between the logo and the headline, so they never overlap. */}
          <div className="absolute inset-x-0 top-[8%] h-[50%]">
            <Suspense fallback={null}>
              <HeroScene />
            </Suspense>
          </div>
        </SceneBoundary>
      )}
      <div className="pointer-events-none relative flex items-center gap-3">
        <LogoMark size={36} title="" />
        <span className="font-display text-xl font-bold">Untangle</span>
      </div>
      <div className="pointer-events-none relative max-w-lg">
        <p className="font-display text-6xl leading-[0.95] font-bold tracking-tight text-on-brand">
          Every complaint,
          <br />
          untangled.
        </p>
        <p className="mt-6 max-w-sm text-lg text-on-brand/80">
          Every customer complaint, from your agents or your partners, gets an owner, a deadline and
          an escalation path, so nothing slips through.
        </p>
        <ul className="mt-10 flex flex-wrap gap-2">
          {[
            { icon: <Clock className="size-4" />, text: 'SLA timers & auto-escalation' },
            { icon: <Webhook className="size-4" />, text: 'Signed partner webhooks' },
            { icon: <ShieldCheck className="size-4" />, text: 'Role-based access' },
          ].map((item) => (
            <li
              key={item.text}
              className="inline-flex items-center gap-2 rounded-md border-2 border-line bg-paper/80 px-3 py-1.5 text-sm font-semibold text-ink shadow-hard-sm"
            >
              {item.icon}
              {item.text}
            </li>
          ))}
        </ul>
      </div>
    </section>
  );
}
