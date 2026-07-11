/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import solid from 'vite-plugin-solid';

// The backend (civictech.agora.AgoraApp) runs on :8080. We proxy its three
// routes so the dev app is same-origin. /events is SSE: keep it unbuffered —
// http-proxy streams it through as long as nothing compresses it.
export default defineConfig({
  plugins: [solid()],
  server: {
    proxy: {
      '/graph': { target: 'http://localhost:8080', changeOrigin: true },
      '/op': { target: 'http://localhost:8080', changeOrigin: true },
      '/events': {
        target: 'http://localhost:8080',
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
