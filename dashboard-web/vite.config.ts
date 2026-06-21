import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// `base` is "/" for local serving (the Java backend serves the SPA at the root) and is overridden to
// "/<repo>/" for the GitHub Pages demo build via VITE_BASE. The dev server proxies the backend API +
// SSE stream to the Java server on :8080 so `npm run dev` gives hot reload against the real engine.
export default defineConfig({
  base: process.env.VITE_BASE ?? "/",
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api": {
        target: "http://localhost:8080",
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: "dist",
    emptyOutDir: true,
  },
});
