/// <reference types="vitest/config" />
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Dev server proxies /api to the Spring api process so the SPA and REST share an origin in development.
// Production is served by nginx (ui/nginx.conf), which enforces the CSP and proxies /api to the api service.
export default defineConfig({
  plugins: [react()],
  build: {
    // CSP is `script-src 'self'` (no 'unsafe-inline'). Vite's module-preload polyfill is injected as an INLINE
    // <script>; disabling it keeps every script external and self-hosted so the build never needs a CSP hash or
    // relaxation (OD-13c). Modern browsers support <link rel="modulepreload"> natively, so this is safe here.
    modulePreload: { polyfill: false },
  },
  server: {
    port: 5173,
    proxy: {
      "/api": {
        target: process.env.SECSCAN_API_URL ?? "http://localhost:8080",
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: "jsdom",
    globals: true,
    include: ["src/**/*.test.{ts,tsx}"],
    // The e2e/ tree is Playwright-driven and run separately (npm run e2e); keep vitest out of it.
    exclude: ["e2e/**", "node_modules/**", "dist/**"],
  },
});
