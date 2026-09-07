<script lang="ts">
  import { CalendarDays, CalendarRange, Download, LayoutGrid, Settings2, Sparkles } from 'lucide-svelte';
  import { onDestroy, onMount } from 'svelte';
  import Today from './lib/pages/Today.svelte'; import Week from './lib/pages/Week.svelte'; import ImportCenter from './lib/pages/ImportCenter.svelte'; import Widgets from './lib/pages/Widgets.svelte'; import Settings from './lib/pages/Settings.svelte';
  import type { Course, GlassSettings, PageId } from './lib/types';
  import { courses as demoCourses, defaultGlass } from './lib/state';
  import { getBootstrap, listScheduleCourses, publishWidgetSnapshot } from './lib/tauri';
  let page: PageId = 'today'; let glass: GlassSettings = { ...defaultGlass }; let runtimeCourses: Course[] = demoCourses; let liveData = false;
  const nav = [
    { id: 'today' as PageId, label: '今日', icon: CalendarDays }, { id: 'week' as PageId, label: '周课表', icon: CalendarRange },
    { id: 'import' as PageId, label: '导入', icon: Download }, { id: 'widgets' as PageId, label: '小组件', icon: LayoutGrid }, { id: 'settings' as PageId, label: '设置', icon: Settings2 }
  ];
  async function loadRuntimeData() {
    try {
      const [bootstrap, loaded] = await Promise.all([getBootstrap(), listScheduleCourses()]);
      if (bootstrap.glassSettings) glass = { ...defaultGlass, ...bootstrap.glassSettings };
      if (loaded.length) { runtimeCourses = loaded; liveData = true; }
    } catch {
      const saved = localStorage.getItem('luma.glass'); if (saved) { try { glass = { ...defaultGlass, ...JSON.parse(saved) }; } catch {} }
    }
    const next = runtimeCourses.find((course) => course.day === 1) ?? runtimeCourses[0];
    if (next) publishWidgetSnapshot({ courseName: next.name, courseMeta: `${next.start || '待定'}–${next.end || '待定'} · ${next.room || '教室待定'}${next.teacher ? ` · ${next.teacher}` : ''}`, countdown: '查看课表' });
  }
  function onDataChanged() { loadRuntimeData(); }
  onMount(() => { loadRuntimeData(); window.addEventListener('luma-data-changed', onDataChanged); });
  onDestroy(() => window.removeEventListener('luma-data-changed', onDataChanged));
  $: cssVars = `--glass-blur:${glass.blur}px;--glass-opacity:${glass.opacity / 100};--glass-sat:${glass.saturation}%;--glass-highlight:${glass.highlight / 100};--glass-refraction:${glass.refraction / 100};--glass-noise:${glass.noise / 100}`;
</script>
<div class="app-shell" style={cssVars} class:motion={glass.motion}>
  <div class="ambient ambient-a"></div><div class="ambient ambient-b"></div>
  <aside class="sidebar glass-panel">
    <button class="brand" aria-label="LumaSchedule"><span class="brand-mark"><Sparkles size={20} strokeWidth={1.8} /></span><span class="brand-copy"><b>Luma</b><small>Schedule</small></span></button>
    <nav class="desktop-nav">{#each nav as item}<button class:active={page === item.id} on:click={() => (page = item.id)}><svelte:component this={item.icon} size={20} strokeWidth={1.75} /><span>{item.label}</span></button>{/each}</nav>
    <div class="sidebar-status"><span class="status-dot"></span><div><b>{liveData ? '本地数据库' : '离线演示'}</b><small>{liveData ? 'SQLite 已载入' : '导入课表后切换'}</small></div></div>
  </aside>
  <main class="main-stage">
    {#if page === 'today'}<Today courses={runtimeCourses} />{:else if page === 'week'}<Week courses={runtimeCourses} />{:else if page === 'import'}<ImportCenter on:imported={loadRuntimeData} />{:else if page === 'widgets'}<Widgets />{:else}<Settings bind:glass />{/if}
  </main>
  <nav class="mobile-nav glass-panel">{#each nav as item}<button class:active={page === item.id} on:click={() => (page = item.id)} aria-label={item.label}><svelte:component this={item.icon} size={20} strokeWidth={1.8} /><span>{item.label}</span></button>{/each}</nav>
</div>
