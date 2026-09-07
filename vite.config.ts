import { defineConfig } from 'vite';
import { svelte } from '@sveltejs/vite-plugin-svelte';
import { fileURLToPath, URL } from 'node:url';

export default defineConfig({
  plugins: [svelte()],
  clearScreen: false,
  resolve: {
    alias: {
      '@tauri-apps/plugin-dialog': fileURLToPath(new URL('./src/lib/shims/dialog.ts', import.meta.url))
    }
  },
  server: {
    port: 1420,
    strictPort: true,
    host: false
  },
  envPrefix: ['VITE_'],
  build: {
    // Android 8+ WebView is Chromium-based; keep the output modern and compact.
    target: 'chrome100',
    minify: 'esbuild',
    sourcemap: false,
    cssCodeSplit: true
  }
});
