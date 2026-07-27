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
  test: {
    environment: 'node',
    include: ['test/**/*.test.ts'],
  },
});
