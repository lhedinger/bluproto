import { defineConfig } from 'vite';

// Dev: `npm run dev -- --host` serves on :5173 and proxies the API (including
// the WebSocket) to the Java backend on :7070 — open the page from a phone on
// the same LAN to feel the touch gestures for real.
// Prod: `npm run build` emits dist/, which the backend serves directly.
export default defineConfig({
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:7070',
        ws: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    target: 'es2022',
  },
});
