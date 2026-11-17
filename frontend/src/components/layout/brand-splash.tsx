import { LogoMark } from '@/components/brand/LogoMark';

/** Full-screen placeholder while the session is restored (usually < 100 ms). */
export function BrandSplash() {
  return (
    <div
      role="status"
      aria-label="Loading Untangle"
      className="grid min-h-dvh place-items-center bg-paper"
    >
      <div className="flex animate-pulse items-center gap-3">
        <LogoMark size={44} title="" />
        <span className="font-display text-3xl font-bold">Untangle</span>
      </div>
    </div>
  );
}
