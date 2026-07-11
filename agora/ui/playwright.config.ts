import { defineConfig } from '@playwright/test';

// Smoke test only. Vite is started/reused via webServer; the agora backend
// (./gradlew :agora:run) must already be running on :8080 for the proxy.
export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  fullyParallel: false,
  use: { baseURL: 'http://localhost:5173' },
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:5173',
    reuseExistingServer: true,
    timeout: 30_000,
  },
});
