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
  // picks the real reactive build instead. This was already in place for the
  // node-environment suites; it turns out to be exactly the same fix
  // FE-TESTS needed for the new jsdom suites too — Vitest picks the
  // resolution condition from *whether VITEST is set*, not from the
  // per-file `environment`, so a `/** @vitest-environment jsdom */` suite
  // hits the identical wrong-export-condition problem underneath its jsdom
  // globals. Confirmed empirically by temporarily removing this line: every
  // DOM suite then failed outright (not a silent empty render) with `Error:
  // Client-only API called on the server side` thrown from
  // `solid-js/web/dist/server.js`, the moment a component under test called
  // one of the APIs the SSR build stubs out. No second/different fix was
  // needed for the jsdom suites beyond this pre-existing line — it is the
  // one non-obvious thing FE-TESTS' own gotcha warning was pointing at.
  resolve: process.env.VITEST ? { conditions: ['browser'] } : undefined,
  test: {
    environment: 'node',
    // FE-TESTS ticket: `.test.tsx` picks up the new `test/dom/**` DOM
    // suites; the existing `.test.ts` suites (36 at the time of this ticket
    // — more than the "24" the ticket text itself cites, since later waves'
    // tickets landed a few more before this one ran) are untouched and keep
    // running in the fast default `node` environment. Each DOM suite opts
    // into `jsdom` itself via a `/** @vitest-environment jsdom */` docblock
    // at the top of the file (Vitest's per-file environment override) —
    // there is no jsdom/DOM cost paid by any suite that doesn't ask for it.
    include: ['test/**/*.test.ts', 'test/**/*.test.tsx'],
  },
});
