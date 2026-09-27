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
  plugins: [solid()],
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
    include: ['test/**/*.test.ts'],
  },
});
