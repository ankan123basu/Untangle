import { useEffect, useState } from 'react';

/** Re-renders once per `ms`; the countdowns tick without refetching anything. */
export function useNow(ms = 1000): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = window.setInterval(() => {
      setNow(Date.now());
    }, ms);
    return () => {
      window.clearInterval(timer);
    };
  }, [ms]);
  return now;
}
