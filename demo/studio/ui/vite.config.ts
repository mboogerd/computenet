/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import solid from 'vite-plugin-solid';

const backend = `http://127.0.0.1:${process.env.STUDIO_BACKEND_PORT ?? 18440}`;
export default defineConfig({
  plugins: [solid()],
  server: {
    proxy: {
      '/api': { target: backend, changeOrigin: true },
      '/events': { target: backend, changeOrigin: true },
    },
  },
  build: { outDir: 'dist' },
  test: { environment: 'node', include: ['test/**/*.test.ts'] },
});
