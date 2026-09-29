/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import solid from 'vite-plugin-solid';

// The backend (civictech.deliberate) listens on :8091 by default; override
// with DELIBERATE_BACKEND. /events is SSE and streams through http-proxy
// unbuffered. `base: './'` keeps built asset URLs relative, because the
// backend serves ui/dist at `/`.
const backend = process.env.DELIBERATE_BACKEND ?? 'http://localhost:8091';
const route = { target: backend, changeOrigin: true };

export default defineConfig({
  base: './',
  // Vitest renders components through Solid's server renderer (the `ssr`
  // project); production remains a normal browser build. test/dom suites,
  // which need real DOM nodes, compile for the client (vitest.workspace.ts).
  plugins: [solid({ ssr: process.env.VITEST === 'true' })],
  server: {
    proxy: {
      '/graph': route,
      '/events': route,
      '/question': route,
      '/override': route,
    },
  },
  test: {
    environment: 'node',
    include: ['test/**/*.test.{ts,tsx}'],
    // test/dom runs in its own project (vitest.workspace.ts): client build, jsdom.
    exclude: ['test/dom/**', '**/node_modules/**'],
  },
});
