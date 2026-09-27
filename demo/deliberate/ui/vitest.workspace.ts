import solid from 'vite-plugin-solid';
import { defineWorkspace } from 'vitest/config';

// Two projects. `ssr`: most suites render components to strings through
// Solid's server build (vite.config.ts). `dom`: the few suites that need live
// DOM nodes — identity across frames, focus, hover, toasts — compile for the
// client and run in jsdom. The "browser" condition makes solid-js resolve to
// its reactive client build instead of the server stub under Vitest.
export default defineWorkspace([
  { extends: './vite.config.ts', test: { name: 'ssr' } },
  {
    plugins: [solid()],
    resolve: { conditions: ['browser', 'development'] },
    test: {
      name: 'dom',
      environment: 'jsdom',
      include: ['test/dom/**/*.test.tsx'],
      // Inlined so Vite resolves solid-js with the conditions above; left to
      // Node it would load the non-reactive server build.
      server: { deps: { inline: [/solid-js/] } },
    },
  },
]);
