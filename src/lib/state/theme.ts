import { systemPrefersDark } from '../native/bridge';
import type { ThemeMode } from '../types';

export function resolvedTheme(mode: ThemeMode, systemDark: boolean): 'light' | 'dark' {
  if (mode === 'system') return systemDark ? 'dark' : 'light';
  return mode;
}

/** Applies the cached theme before the first paint to avoid a light flash in dark mode. */
export function applyCachedTheme() {
  try {
    const stored = JSON.parse(localStorage.getItem('luma.appearance.v2') || 'null') as { theme?: ThemeMode; accent?: string } | null;
    const mode: ThemeMode = stored?.theme === 'light' || stored?.theme === 'dark' ? stored.theme : 'system';
    const root = document.documentElement;
    root.dataset.theme = resolvedTheme(mode, systemPrefersDark());
    if (stored?.accent) root.dataset.accent = stored.accent;
  } catch {
    // Fall back to the light theme.
  }
}
