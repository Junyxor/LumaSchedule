import { defineConfig } from 'vite';
import { svelte } from '@sveltejs/vite-plugin-svelte';

export default defineConfig({
  plugins: [svelte()],
  clearScreen: false,
  server: {
    port: 1420,
    strictPort: true,
    host: false
  },
  envPrefix: ['VITE_'],
  build: {
    // Android 8+ WebView is Chromium-based; keep the output modern and compact.
    target: 'chrome100',
    minify: 'oxc',
    sourcemap: false,
    cssCodeSplit: true
  }
});
