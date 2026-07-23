/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import solid from 'vite-plugin-solid';

// The backend (civictech.agora.AgoraApp) runs on :8080 by default. We proxy its
// three routes so the dev app is same-origin. Override with AGORA_BACKEND when
// the default port is taken by another demo/session. /events is SSE: keep it
// unbuffered — http-proxy streams it through as long as nothing compresses it.
const backend = process.env.AGORA_BACKEND ?? 'http://localhost:8080';
export default defineConfig({
  plugins: [solid()],
  server: {
    proxy: {
      '/graph': { target: backend, changeOrigin: true },
      '/op': { target: backend, changeOrigin: true },
      '/events': {
        target: backend,
        changeOrigin: true,
        // ponytail: SSE needs no special proxy flags here — http-proxy passes
        // the stream unbuffered. If a frame ever stalls, the culprit is
        // compression, not this config.
      },
    },
  },
  test: {
    environment: 'node',
    include: ['test/**/*.test.ts'],
  },
});
