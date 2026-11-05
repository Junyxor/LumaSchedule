import { mount } from 'svelte';
import './app.css';
import App from './App.svelte';
import { applyNativeInsets } from './lib/native/bridge';
import { applyCachedTheme } from './lib/state/theme';

async function boot() {
  // A browser preview (`npm run dev` / `npm run build:mock`) has no Android bridge.
  if (import.meta.env.MODE !== 'production' && !window.LumaNative) {
    const { installMockNative } = await import('./lib/native/mock');
    installMockNative();
  }
  applyNativeInsets();
  applyCachedTheme();
  mount(App, { target: document.getElementById('app')! });
}

void boot();
