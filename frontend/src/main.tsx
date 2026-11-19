import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
// Self-hosted variable fonts: no third-party font CDN, so the CSP stays `font-src 'self'`.
import '@fontsource-variable/space-grotesk';
import '@fontsource-variable/inter';
import '@fontsource-variable/jetbrains-mono';
import '@/styles/globals.css';
import { App } from '@/app/App';
import { installStaleBuildRecovery } from '@/lib/stale-build';

installStaleBuildRecovery();

const container = document.getElementById('root');
if (!container) {
  throw new Error('Root element #root not found in index.html');
}

createRoot(container).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
