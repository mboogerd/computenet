/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import solid from 'vite-plugin-solid';

// The :inspect server (civictech.inspect.InspectorServer) runs on its own
// port beside the pilot demo (skillmatch), default 7071 (see 20-api-contract.md).
// Override with INSPECT_BACKEND when the default port is taken by another
// demo/session. /api/inspect/events is SSE: keep it unbuffered — http-proxy
// streams it through as long as nothing compresses it (agora/ui precedent).
const backend = process.env.INSPECT_BACKEND ?? 'http://localhost:7071';
export default defineConfig({
  plugins: [solid()],
  server: {
    proxy: {
      '/api/inspect': { target: backend, changeOrigin: true },
    },
  },
  // Under `environment: 'node'`, Vitest's SSR pipeline otherwise resolves
  // solid-js via its "node" export condition (`solid-js/dist/server.js`) —
  // the SSR build, whose `createEffect` is a render-time no-op rather than a
  // reactive subscription. That silently breaks any test exercising a
  // `createEffect`-driven bridge (e.g. `solid/detail.ts`'s `initDetail()`).
  // Forcing the "browser" condition for module resolution (test runs only —
  // `VITEST` is set by the test runner, never during `vite`/`vite build`)
  // picks the real reactive build instead; none of this suite's tests touch
  // the DOM, so no jsdom/happy-dom environment is needed for that.
  resolve: process.env.VITEST ? { conditions: ['browser'] } : undefined,
  test: {
    environment: 'node',
    include: ['test/**/*.test.ts'],
  },
});
