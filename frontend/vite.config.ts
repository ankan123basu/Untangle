import { fileURLToPath, URL } from 'node:url';

import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

// One config for the dev server, the production build and Vitest.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    strictPort: true,
    // In dev the SPA runs on :5173 and the API on :8080. Proxying /api keeps the browser on a
    // single origin, exactly like nginx does in Docker, so we never need CORS for the app itself.
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: false },
    },
  },
  build: {
    sourcemap: true,
    target: 'es2023',
    // Never inline fonts as data: URIs; the nginx CSP only allows font-src 'self'.
    // Returning undefined keeps Vite's default size-based rule for everything else.
    assetsInlineLimit: (file) => (/\.(woff2?|ttf|otf)$/.test(file) ? false : undefined),
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: true,
    include: ['src/**/*.test.{ts,tsx}'],
    restoreMocks: true,
  },
});
