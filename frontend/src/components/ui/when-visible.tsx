import { useEffect, useRef, useState, type ReactNode } from 'react';

/**
 * Renders `children` once this spot is near the viewport and the browser is idle (and keeps them
 * afterwards). Defers heavy widgets, such as the chart, so they don't compete with the first paint.
 */
export function WhenVisible({
  children,
  fallback,
  rootMargin = '200px',
  ready = true,
  delay = 0,
}: {
  children: ReactNode;
  fallback: ReactNode;
  rootMargin?: string;
  /** Extra gate, e.g. "the page's key numbers have arrived". */
  ready?: boolean;
  /** Minimum wait (ms) after becoming ready, to keep big downloads clear of the first paint. */
  delay?: number;
}) {
  const ref = useRef<HTMLDivElement>(null);
  // Without IntersectionObserver (very old browsers), just render straight away.
  const [visible, setVisible] = useState(() => typeof IntersectionObserver === 'undefined');
  useEffect(() => {
    const node = ref.current;
    if (!node || visible || !ready) return;
    let idle: number | undefined;
    const observer = new IntersectionObserver(
      (entries) => {
        if (!entries.some((e) => e.isIntersecting)) return;
        observer.disconnect();
        // Wait (then for an idle moment) so the first paint isn't competing with this download.
        idle = window.setTimeout(() => {
          if (typeof window.requestIdleCallback === 'function') {
            window.requestIdleCallback(
              () => {
                setVisible(true);
              },
              { timeout: 1500 },
            );
          } else {
            setVisible(true);
          }
        }, delay);
      },
      { rootMargin },
    );
    observer.observe(node);
    return () => {
      observer.disconnect();
      if (idle !== undefined) window.clearTimeout(idle);
    };
  }, [visible, rootMargin, ready, delay]);
  return <div ref={ref}>{visible ? children : fallback}</div>;
}
