/*
 * After a new version is deployed, a tab that was opened earlier still runs the old code and asks
 * for old, hash-named chunks that no longer exist (404). Instead of showing an error page, reload
 * once so the tab picks up the new version. A short guard stops reload loops if the server itself
 * is broken.
 */

const KEY = 'untangle.stale-build-reload';
const GUARD_MS = 15_000;

const CHUNK_ERROR =
  /Failed to fetch dynamically imported module|Importing a module script failed|error loading dynamically imported module|Unable to preload CSS|ChunkLoadError/i;

/** True when an error means "this tab's code is older than the server's". */
export function isStaleBuildError(error: unknown): boolean {
  const message =
    error instanceof Error
      ? `${error.name} ${error.message}`
      : typeof error === 'string'
        ? error
        : '';
  return CHUNK_ERROR.test(message);
}

/** True if we reloaded for a new version moments ago (so reloading again would loop). */
export function reloadedRecently(): boolean {
  try {
    return Date.now() - Number(sessionStorage.getItem(KEY) ?? 0) < GUARD_MS;
  } catch {
    return false;
  }
}

/** Reloads the page unless we already did so in the last few seconds. Returns whether it did. */
export function reloadForNewVersion(): boolean {
  if (reloadedRecently()) return false;
  try {
    sessionStorage.setItem(KEY, String(Date.now()));
  } catch {
    /* storage blocked: still reload once */
  }
  window.location.reload();
  return true;
}

/** Vite fires this when a lazy chunk's preload fails (the usual stale-build symptom). */
export function installStaleBuildRecovery(): void {
  window.addEventListener('vite:preloadError', (event) => {
    if (reloadForNewVersion()) event.preventDefault();
  });
}
