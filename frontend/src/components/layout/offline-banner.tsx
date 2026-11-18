import { useSyncExternalStore } from 'react';
import { WifiOff } from 'lucide-react';

function subscribe(onChange: () => void) {
  window.addEventListener('online', onChange);
  window.addEventListener('offline', onChange);
  return () => {
    window.removeEventListener('online', onChange);
    window.removeEventListener('offline', onChange);
  };
}

/** Screen 10 (§12.5): a clear banner when the browser goes offline, instead of mystery errors. */
export function OfflineBanner() {
  const online = useSyncExternalStore(
    subscribe,
    () => navigator.onLine,
    () => true,
  );
  if (online) return null;
  return (
    <div
      role="status"
      className="flex items-center justify-center gap-2 border-b border-line bg-prio-high-bg px-4 py-2 text-sm font-semibold text-prio-high-fg"
    >
      <WifiOff aria-hidden="true" className="size-4" />
      You’re offline. Changes won’t be saved until you reconnect.
    </div>
  );
}
