import { Component, lazy, memo, Suspense, useState, type ReactNode } from 'react';
import { BellRing, Clock, ShieldCheck, Webhook } from 'lucide-react';
import { LogoMark } from '@/components/brand/LogoMark';
import { useThemeColors } from '@/components/three/theme-colors';
import { useScene3d } from '@/hooks/use-theme';
import { canRunHeroScene } from './hero-support';

// three.js stays out of the main bundle (ADR-024): this chunk loads only when the scene will run.
const UntangleScene = lazy(() => import('./untangle-scene'));

/** If WebGL fails at runtime (context lost, driver bug), quietly show the static art instead. */
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

const LANES = ['#f43f5e', '#f59e0b', '#60a5fa', '#a3b1c6'] as const;

/**
 * The same story as the 3D scene, drawn once in SVG: four tangled priority threads pulled straight
 * into a resolved ticket. Shown with reduced motion, without WebGL, with 3D switched off, and while
 * the scene's chunk loads.
 */
function StaticArt() {
  return (
    <div className="absolute inset-0 flex items-center justify-center">
      <svg viewBox="0 0 640 300" className="h-full max-h-[340px] w-full max-w-[680px]">
        <g fill="none" strokeLinecap="round" strokeWidth="5">
          {LANES.map((color, i) => (
            <path
              key={color}
              stroke={color}
              opacity="0.9"
              d={`M40 ${String(130 + i * 12)} c40 -90 90 70 60 -20 s-70 -40 -20 40 s80 30 60 -30 S200 ${String(
                110 + i * 26,
              )} 250 ${String(105 + i * 30)} L390 ${String(105 + i * 30)}`}
            />
          ))}
        </g>
      </svg>
      <div className="absolute top-1/2 right-[6%] w-56 -translate-y-1/2 rotate-[-4deg] rounded-2xl border border-white/30 bg-white/10 p-4 text-white shadow-2xl backdrop-blur-xl">
        <div className="flex items-center justify-between font-mono text-xs text-white/80">
          UNT-000123
          <span className="rounded-full bg-emerald-500/90 px-2 py-0.5 font-sans text-[10px] font-bold">
            RESOLVED
          </span>
        </div>
        <p className="mt-2 font-display text-lg font-bold">Card charged twice</p>
        <div className="mt-3 h-1.5 rounded-full bg-emerald-400/80" />
      </div>
    </div>
  );
}

const TILES = [
  { icon: <Clock className="size-4" />, value: '1 h', text: 'Critical SLA, auto-escalates' },
  { icon: <Webhook className="size-4" />, value: 'HMAC', text: 'Signed partner webhooks' },
  { icon: <ShieldCheck className="size-4" />, value: '3 roles', text: 'Agent · Lead · Admin' },
  { icon: <BellRing className="size-4" />, value: 'Live', text: 'Notifications & timelines' },
];

/**
 * Left column of the login (lg and up): brand, the 3D story in its own stage, then the headline
 * and four facts. Every block has its own row, so text and 3D never overlap.
 */
export const LoginHero = memo(function LoginHero() {
  const [capable] = useState(canRunHeroScene);
  const [choice] = useScene3d();
  const colors = useThemeColors();
  const show3d = capable && choice !== 'off';
  return (
    <section
      aria-hidden="true"
      className="relative hidden min-h-dvh flex-col gap-6 p-10 text-white lg:flex xl:p-14"
    >
      <div className="flex items-center gap-3">
        <LogoMark size={38} title="" />
        <span className="font-display text-xl font-bold tracking-tight">Untangle</span>
        <span className="ml-2 rounded-full border border-white/20 bg-white/10 px-3 py-1 text-xs font-semibold text-white/85 backdrop-blur-md">
          Support · Escalation · SLA
        </span>
      </div>

      <div className="relative min-h-[240px] flex-1">
        {show3d ? (
          <SceneBoundary fallback={<StaticArt />}>
            <Suspense fallback={<StaticArt />}>
              <UntangleScene colors={colors} />
            </Suspense>
          </SceneBoundary>
        ) : (
          <StaticArt />
        )}
      </div>

      <div className="max-w-2xl">
        <p className="font-display text-6xl leading-[0.95] font-bold tracking-tight xl:text-7xl">
          Every complaint,
          <br />
          <span className="text-aurora">untangled.</span>
        </p>
        <p className="mt-5 max-w-lg text-lg text-white/75">
          Every customer complaint, from your agents or your partners, gets an owner, a deadline and
          an escalation path, so nothing slips through.
        </p>
      </div>

      <ul className="grid max-w-3xl grid-cols-2 gap-3 xl:grid-cols-4">
        {TILES.map((tile) => (
          <li
            key={tile.text}
            className="rounded-2xl border border-white/15 bg-white/[0.07] p-3.5 shadow-[inset_0_1px_0_rgb(255_255_255/0.15)] backdrop-blur-md"
          >
            <span className="flex items-center gap-2 text-white/70">
              {tile.icon}
              <span className="font-display text-lg font-bold text-white">{tile.value}</span>
            </span>
            <span className="mt-1 block text-xs leading-snug text-white/70">{tile.text}</span>
          </li>
        ))}
      </ul>
    </section>
  );
});
