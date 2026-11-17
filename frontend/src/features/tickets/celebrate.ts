import { prefersReducedMotion } from '@/hooks/use-theme';

/**
 * A restrained "resolved!" burst (§12.3): a few brand-coloured sparks from the centre of the
 * screen, drawn with the Web Animations API on short-lived DOM nodes. Skipped entirely under
 * reduced motion. Phase 9 may swap in a React Bits effect.
 */
export function celebrate(): void {
  if (typeof document === 'undefined' || prefersReducedMotion()) return;
  if (typeof Element.prototype.animate !== 'function') return;
  const layer = document.createElement('div');
  layer.setAttribute('aria-hidden', 'true');
  layer.style.cssText = 'position:fixed;inset:0;pointer-events:none;z-index:60;';
  document.body.appendChild(layer);
  const colours = ['var(--ut-brand)', 'var(--ut-accent)', 'var(--ut-success)', 'var(--ut-ink)'];
  const x = window.innerWidth / 2;
  const y = window.innerHeight / 3;
  const sparks = 18;
  for (let i = 0; i < sparks; i++) {
    const spark = document.createElement('span');
    const size = 6 + (i % 3) * 3;
    spark.style.cssText = `position:absolute;left:${String(x)}px;top:${String(y)}px;width:${String(size)}px;height:${String(size)}px;background:${colours[i % colours.length] ?? 'black'};border:2px solid var(--ut-line);`;
    layer.appendChild(spark);
    const angle = (i / sparks) * Math.PI * 2;
    const distance = 90 + (i % 4) * 30;
    spark.animate(
      [
        { transform: 'translate(-50%,-50%) scale(1) rotate(0deg)', opacity: 1 },
        {
          transform: `translate(calc(-50% + ${String(Math.cos(angle) * distance)}px), calc(-50% + ${String(Math.sin(angle) * distance)}px)) scale(0.4) rotate(180deg)`,
          opacity: 0,
        },
      ],
      { duration: 700, easing: 'cubic-bezier(0.2, 0.8, 0.2, 1)', fill: 'forwards' },
    );
  }
  window.setTimeout(() => {
    layer.remove();
  }, 800);
}
