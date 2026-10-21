import { defineConfig, devices } from "@playwright/test";

// Chromium only, run inside the Playwright Docker image (change 6) joined to the secscan-e2e network.
// baseURL is the e2e ui service. Retries 0 — a flake must be fixed, not retried (OD-18 / harness spec).
const baseURL = process.env.E2E_BASE_URL ?? "http://ui:80";

export default defineConfig({
  testDir: "./tests",
  // Both inside the mounted artifacts/ bind, but distinct subdirs — Playwright clears outputDir on start and
  // cannot rmdir the bind mount root itself, and the html report folder must not clash with outputDir.
  outputDir: "./artifacts/test-results",
  fullyParallel: false,
  forbidOnly: true,
  retries: 0,
  workers: 1,
  reporter: [["list"], ["html", { outputFolder: "./artifacts/report", open: "never" }]],
  use: {
    baseURL,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: "off",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
});
