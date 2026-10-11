import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Dev server proxies /api to the Spring api process so the SPA and REST share an origin in development.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api": {
        target: process.env.SECSCAN_API_URL ?? "http://localhost:8080",
        changeOrigin: true,
      },
    },
  },
});
