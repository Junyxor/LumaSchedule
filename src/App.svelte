<script lang="ts">
  import { CalendarDays, CalendarRange, Download, GraduationCap, Settings2 } from 'lucide-svelte';
  import { onMount } from 'svelte';
  import { importSession } from './lib/import/session.svelte';
  import GradesPage from './lib/pages/GradesPage.svelte';
  import ImportPage from './lib/pages/ImportPage.svelte';
  import SettingsPage from './lib/pages/SettingsPage.svelte';
  import TodayPage from './lib/pages/TodayPage.svelte';
  import WeekPage from './lib/pages/WeekPage.svelte';
  import CourseDetailSheet from './lib/schedule/CourseDetailSheet.svelte';
  import CourseEditorSheet from './lib/schedule/CourseEditorSheet.svelte';
  import { systemPrefersDark } from './lib/native/bridge';
  import { app } from './lib/state/app.svelte';
  import { back } from './lib/state/back.svelte';
  import { resolvedTheme } from './lib/state/theme';
  import type { PageId } from './lib/types';
  import ConfirmHost from './lib/ui/ConfirmHost.svelte';
  import Toasts from './lib/ui/Toasts.svelte';

  const nav: { id: PageId; label: string; icon: typeof CalendarDays }[] = [
    { id: 'today', label: '今日', icon: CalendarDays },
    { id: 'week', label: '课表', icon: CalendarRange },
    { id: 'grades', label: '成绩', icon: GraduationCap },
    { id: 'import', label: '导入', icon: Download },
    { id: 'settings', label: '设置', icon: Settings2 }
  ];

  const media = window.matchMedia('(prefers-color-scheme: dark)');
  let systemDark = $state(systemPrefersDark());
  const theme = $derived(resolvedTheme(app.appearance.theme, systemDark));

  $effect(() => {
    const root = document.documentElement;
    root.dataset.theme = theme;
    root.dataset.accent = app.appearance.accent;
    window.LumaNative?.setSystemBarsDark?.(theme === 'dark');
  });

  const backEnabled = $derived(back.depth > 0 || app.page !== 'today');
  $effect(() => {
    window.LumaNative?.setBackEnabled?.(backEnabled);
  });

  onMount(() => {
    window.__lumaBack = () => {
      if (back.handle()) return true;
      if (app.page !== 'today') {
        app.go('today');
        return true;
      }
      return false;
    };
    const onScheme = () => (systemDark = systemPrefersDark());
    // Kotlin reports Android's dark mode directly; WebView's media query can lag behind it.
    const onNativeScheme = (event: Event) => {
      const dark = (event as CustomEvent<{ dark?: boolean }>).detail?.dark;
      systemDark = typeof dark === 'boolean' ? dark : systemPrefersDark();
    };
    const onVisible = () => {
      if (document.visibilityState !== 'visible') return;
      app.tick();
      void app.reload();
    };
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') window.__lumaBack?.();
    };
    media.addEventListener('change', onScheme);
    window.addEventListener('luma-system-theme', onNativeScheme);
    document.addEventListener('visibilitychange', onVisible);
    window.addEventListener('keydown', onKey);
    const clock = setInterval(() => app.tick(), 30_000);
    void app.start().then(() => importSession.resume());
    return () => {
      media.removeEventListener('change', onScheme);
      window.removeEventListener('luma-system-theme', onNativeScheme);
      document.removeEventListener('visibilitychange', onVisible);
      window.removeEventListener('keydown', onKey);
      clearInterval(clock);
    };
  });
</script>

<div class="shell">
  <nav class="tabbar" aria-label="主导航">
    {#each nav as item (item.id)}
      {@const Icon = item.icon}
      <button class:active={app.page === item.id} type="button" aria-current={app.page === item.id ? 'page' : undefined} onclick={() => app.go(item.id)}>
        <span class="tab-icon"><Icon size={21} strokeWidth={app.page === item.id ? 2.25 : 1.9} /></span>
        <span class="tab-label">{item.label}</span>
      </button>
    {/each}
  </nav>

  <main>
    {#if app.page === 'today'}
      <TodayPage />
    {:else if app.page === 'week'}
      <WeekPage />
    {:else if app.page === 'grades'}
      <GradesPage />
    {:else if app.page === 'import'}
      <ImportPage />
    {:else}
      <SettingsPage />
    {/if}
  </main>
</div>

<CourseDetailSheet />
<CourseEditorSheet />
<ConfirmHost />
<Toasts />

<style>
  .shell {
    min-height: 100vh;
    min-height: 100dvh;
  }

  .tabbar {
    position: fixed;
    right: 0;
    bottom: 0;
    left: 0;
    z-index: 100;
    display: flex;
    height: calc(var(--tabbar-height) + var(--inset-bottom));
    padding: 0 calc(var(--inset-right) + 4px) var(--inset-bottom) calc(var(--inset-left) + 4px);
    border-top: 1px solid var(--border);
    background: var(--surface);
  }

  .tabbar button {
    display: grid;
    flex: 1 1 0;
    align-content: center;
    justify-items: center;
    gap: 2px;
    min-width: 0;
    border: 0;
    background: none;
    color: var(--text-3);
  }

  .tab-icon {
    display: grid;
    place-items: center;
    width: 54px;
    height: 28px;
    border-radius: 14px;
    transition: background-color 0.2s;
  }

  .tab-label {
    font-size: var(--text-xs);
    font-weight: 550;
    line-height: 1.2;
  }

  .tabbar button.active {
    color: var(--accent);
  }

  .tabbar button.active .tab-icon {
    background: var(--accent-soft);
  }

  @media (min-width: 900px) {
    .tabbar {
      top: 0;
      right: auto;
      width: 88px;
      height: auto;
      flex-direction: column;
      justify-content: flex-start;
      gap: 6px;
      padding: calc(var(--inset-top) + 24px) 0 24px calc(var(--inset-left));
      border-top: 0;
      border-right: 1px solid var(--border);
    }

    .tabbar button {
      flex: none;
      height: 64px;
    }

    main {
      padding-left: 88px;
      --tabbar-height: 0px;
    }
  }
</style>
