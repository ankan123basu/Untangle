import { useRef, type ReactNode } from 'react';
import { prefersReducedMotion } from '@/hooks/use-theme';

/**
 * A gentle 3D tilt that follows the pointer (§12.3, KPI cards). Mouse/pen only: touch users and
 * people who prefer reduced motion get a flat card. Plain pointer events and a CSS transform (with
 * a short transition for smoothing), so the dashboard doesn't need the animation library.
 */
export function TiltCard({
  children,
  className,
  max = 7,
}: {
  children: ReactNode;
  className?: string;
  max?: number;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const set = (rx: number, ry: number) => {
    if (ref.current) {
      ref.current.style.transform = `perspective(700px) rotateX(${rx.toFixed(2)}deg) rotateY(${ry.toFixed(2)}deg)`;
    }
  };
  return (
    <div
      ref={ref}
      className={className}
      style={{ transition: 'transform 160ms var(--ut-ease)', willChange: 'transform' }}
      onPointerMove={(e) => {
        if (e.pointerType === 'touch' || !ref.current || prefersReducedMotion()) return;
        const box = ref.current.getBoundingClientRect();
        const x = (e.clientX - box.left) / box.width - 0.5;
        const y = (e.clientY - box.top) / box.height - 0.5;
        set(-y * max * 2, x * max * 2);
      }}
      onPointerLeave={() => {
        set(0, 0);
      }}
    >
      {children}
    </div>
  );
}
