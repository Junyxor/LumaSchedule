<script lang="ts">
  import { CalendarDays, CalendarRange, Download, LayoutGrid, Settings2, Sparkles } from 'lucide-svelte';
  import { onDestroy, onMount } from 'svelte';
  import Today from './lib/pages/Today.svelte';
  import Week from './lib/pages/Week.svelte';
  import ImportCenter from './lib/pages/ImportCenter.svelte';
  import Widgets from './lib/pages/Widgets.svelte';
  import Settings from './lib/pages/Settings.svelte';
  import type { Course, GlassSettings, PageId, ScheduleSnapshot } from './lib/types';
  import { courses as demoCourses, defaultGlass } from './lib/state';
  import { getBootstrap, getScheduleSnapshot, publishWidgetSnapshot } from './lib/tauri';

  let page: PageId = 'today';
  let glass: GlassSettings = { ...defaultGlass };
  let scheduleSnapshot: ScheduleSnapshot = { courses: [] };
  let runtimeCourses: Course[] = [];
  let liveData = false;
  let runtimeReady = false;

  const nav = [
    { id: 'today' as PageId, label: '今日', icon: CalendarDays },
    { id: 'week' as PageId, label: '周课表', icon: CalendarRange },
    { id: 'import' as PageId, label: '导入', icon: Download },
    { id: 'widgets' as PageId, label: '小组件', icon: LayoutGrid },
    { id: 'settings' as PageId, label: '设置', icon: Settings2 }
  ];

  async function loadRuntimeData() {
    try {
      const [bootstrap, snapshot] = await Promise.all([getBootstrap(), getScheduleSnapshot()]);
      if (bootstrap.glassSettings) glass = { ...defaultGlass, ...bootstrap.glassSettings };
      scheduleSnapshot = snapshot;
      runtimeCourses = snapshot.courses;
      liveData = bootstrap.dbReady;
    } catch {
      const saved = localStorage.getItem('luma.glass');
      if (saved) {
        try { glass = { ...defaultGlass, ...JSON.parse(saved) }; } catch {}
      }
      const fallback = import.meta.env.DEV ? demoCourses : [];
      scheduleSnapshot = { courses: fallback };
      runtimeCourses = fallback;
      liveData = false;
    } finally {
      runtimeReady = true;
    }

    if (liveData) {
      const today = ((new Date().getDay() + 6) % 7) + 1;
      const next = runtimeCourses.find((course) => course.day === today) ?? runtimeCourses[0];
      if (next) {
        publishWidgetSnapshot({
          courseName: next.name,
          courseMeta: `${next.start || '待定'}–${next.end || '待定'} · ${next.room || '教室待定'}${next.teacher ? ` · ${next.teacher}` : ''}`,
          countdown: scheduleSnapshot.currentWeek ? `第 ${scheduleSnapshot.currentWeek} 周` : '查看课表'
        });
      }
    }
  }

  function onDataChanged() { loadRuntimeData(); }
  onMount(() => {
    loadRuntimeData();
    window.addEventListener('luma-data-changed', onDataChanged);
  });
  onDestroy(() => window.removeEventListener('luma-data-changed', onDataChanged));

  $: cssVars = `--glass-blur:${glass.blur}px;--glass-opacity:${glass.opacity / 100};--glass-sat:${glass.saturation}%;--glass-highlight:${glass.highlight / 100};--glass-refraction:${glass.refraction / 100};--glass-noise:${glass.noise / 100}`;
</script>

<div class="app-shell" style={cssVars} class:motion={glass.motion} class:runtime-ready={runtimeReady}>
  <div class="ambient ambient-a"></div><div class="ambient ambient-b"></div>
  <aside class="sidebar glass-panel">
    <button class="brand" aria-label="LumaSchedule"><span class="brand-mark"><Sparkles size={20} strokeWidth={1.8} /></span><span class="brand-copy"><b>Luma</b><small>Schedule</small></span></button>
    <nav class="desktop-nav">{#each nav as item}<button class:active={page === item.id} on:click={() => (page = item.id)}><svelte:component this={item.icon} size={20} strokeWidth={1.75} /><span>{item.label}</span></button>{/each}</nav>
    <div class="sidebar-status"><span class="status-dot"></span><div><b>{runtimeReady ? (liveData ? '本地数据库' : '离线模式') : '正在载入'}</b><small>{liveData ? 'SQLite 已就绪' : '等待本地数据'}</small></div></div>
  </aside>
  <main class="main-stage">
    {#if page === 'today'}
      <Today courses={runtimeCourses} currentWeek={scheduleSnapshot.currentWeek} termName={scheduleSnapshot.termName} />
    {:else if page === 'week'}
      <Week courses={runtimeCourses} currentWeek={scheduleSnapshot.currentWeek} termName={scheduleSnapshot.termName} />
    {:else if page === 'import'}
      <ImportCenter on:imported={loadRuntimeData} />
    {:else if page === 'widgets'}
      <Widgets />
    {:else}
      <Settings bind:glass />
    {/if}
  </main>
  <nav class="mobile-nav glass-panel" aria-label="主导航">{#each nav as item}<button class:active={page === item.id} on:click={() => (page = item.id)} aria-label={item.label}><svelte:component this={item.icon} size={20} strokeWidth={1.8} /><span>{item.label}</span></button>{/each}</nav>
</div>
