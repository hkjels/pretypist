import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./test/browser",
  // The selected compact CAD GLB has ~49k triangles. Long end-to-end
  // journeys need more total time on Chromium's headless software renderer;
  // individual assertions retain Playwright's normal, short timeout.
  timeout: 120_000,
  workers: 1,
  use: {
    baseURL: "http://127.0.0.1:4173",
    colorScheme: "dark",
    viewport: { width: 1440, height: 980 },
  },
  webServer: {
    command: "node scripts/serve.mjs 4173",
    url: "http://127.0.0.1:4173",
    reuseExistingServer: true,
  },
});
