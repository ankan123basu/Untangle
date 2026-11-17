import { LogoMark } from '@/components/brand/LogoMark';

/**
 * Left half of the login screen. Phase 9 replaces the static artwork with the lazy-loaded 3D
 * "knot that untangles into a checkmark" scene; this static version stays as the WebGL /
 * reduced-motion fallback.
 */
export function LoginHero() {
  return (
    <section
      aria-hidden="true"
      className="relative hidden flex-col justify-between overflow-hidden border-r-2 border-line bg-brand p-12 lg:flex"
    >
      <div className="absolute -top-24 -right-24 size-96 rounded-full border-2 border-line bg-paper/40" />
      <div className="absolute -bottom-32 -left-16 size-[28rem] rounded-full border-2 border-line bg-accent/15" />
      <div className="relative flex items-center gap-3">
        <LogoMark size={36} title="" />
        <span className="font-display text-xl font-bold">Untangle</span>
      </div>
      <div className="relative max-w-lg">
        <p className="font-display text-6xl leading-[0.95] font-bold tracking-tight text-on-brand">
          Every complaint,
          <br />
          untangled.
        </p>
        <p className="mt-6 max-w-sm text-lg text-on-brand/80">
          Prioritised, owned, tracked and escalated before it rots, from your agents and your
          partners.
        </p>
      </div>
      <p className="relative font-mono text-sm text-on-brand/70">
        UNT · support that keeps its promises
      </p>
    </section>
  );
}
