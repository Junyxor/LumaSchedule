<script lang="ts">
  import { CalendarDays, CalendarRange, Download, LayoutGrid, Settings2, Sparkles } from 'lucide-svelte';
  import { onDestroy, onMount } from 'svelte';
  import Today from './lib/pages/Today.svelte';
  import Week from './lib/pages/Week.svelte';
  import ImportCenter from './lib/pages/ImportCenter.svelte';
  import Widgets from './lib/pages/Widgets.svelte';
  import Settings from './lib/pages/Settings.svelte';
  import type { Course, GlassSettings, PageId, ScheduleSnapshot } from './lib/types';
  import { defaultGlass } from './lib/state';
  import { getBootstrap, getScheduleSnapshot, publishWidgetSnapshot } from './lib/tauri';

  let page: PageId = 'today';
  let glass: GlassSettings = { ...defaultGlass };
  let scheduleSnapshot: ScheduleSnapshot = { courses: [], hasSchedule: false };
  let runtimeCourses: Course[] = [];
  let liveData = false;
  let runtimeReady = false;
  let midnightTimer: ReturnType<typeof setTimeout> | null = null;

  const nav = [
    { id: 'today' as PageId, label: '今日', icon: CalendarDays },
    { id: 'week' as PageId, label: '周课表', icon: CalendarRange },
    { id: 'import' as PageId, label: '导入', icon: Download },
    { id: 'widgets' as PageId, label: '小组件', icon: LayoutGrid },
    { id: 'settings' as PageId, label: '设置', icon: Settings2 }
  ];

  function parseMinutes(value: string) {
    const [hour, minute] = value.split(':').map(Number);
    return Number.isFinite(hour) && Number.isFinite(minute) ? hour * 60 + minute : Number.NaN;
  }

  function nextRemainingCourse(courses: Course[]) {
    const now = new Date();
    const weekday = ((now.getDay() + 6) % 7) + 1;
    const currentMinutes = now.getHours() * 60 + now.getMinutes();
    return [...courses]
      .filter((course) => {
        if (course.day > weekday) return true;
        if (course.day < weekday) return false;
        const end = parseMinutes(course.end);
        return !Number.isFinite(end) || end >= currentMinutes;
      })
      .sort((a, b) => {
        if (a.day !== b.day) return a.day - b.day;
        const aStart = parseMinutes(a.start);
        const bStart = parseMinutes(b.start);
        const aKey = Number.isFinite(aStart) ? aStart : a.startSection * 60;
        const bKey = Number.isFinite(bStart) ? bStart : b.startSection * 60;
        return aKey - bKey;
      })[0] ?? null;
  }

  function readLocalGlass() {
    const saved = localStorage.getItem('luma.glass');
    if (!saved) return null;
    try { return { ...defaultGlass, ...JSON.parse(saved) } as GlassSettings; }
    catch { return null; }
  }

  async function refreshWidgetSnapshot() {
    if (!liveData) return;
    const next = nextRemainingCourse(runtimeCourses);
    if (next) {
      await publishWidgetSnapshot({
        courseName: next.name,
        courseMeta: `${next.start || `第${next.startSection}节`}–${next.end || `第${next.endSection}节`} · ${next.room || '教室待定'}${next.teacher ? ` · ${next.teacher}` : ''}`,
        countdown: scheduleSnapshot.currentWeek ? `第 ${scheduleSnapshot.currentWeek} 周` : '本周'
      });
      return;
    }
    await publishWidgetSnapshot({
      courseName: scheduleSnapshot.hasSchedule ? '当前周没有课程' : '暂无课程',
      courseMeta: scheduleSnapshot.hasSchedule ? '课表已保存，进入教学周后会自动更新' : '打开 LumaSchedule 导入课表',
      countdown: scheduleSnapshot.currentWeek ? `第 ${scheduleSnapshot.currentWeek} 周` : '--'
    });
  }

  async function loadRuntimeData() {
    try {
      const [bootstrap, snapshot] = await Promise.all([getBootstrap(), getScheduleSnapshot()]);
      const localGlass = readLocalGlass();
      glass = localGlass ?? (bootstrap.glassSettings ? { ...defaultGlass, ...bootstrap.glassSettings } : { ...defaultGlass });
      scheduleSnapshot = snapshot;
      runtimeCourses = snapshot.courses;
      liveData = bootstrap.dbReady;
    } catch {
      glass = readLocalGlass() ?? { ...defaultGlass };
      scheduleSnapshot = { courses: [], hasSchedule: false };
      runtimeCourses = [];
      liveData = false;
    } finally {
      runtimeReady = true;
    }
    void refreshWidgetSnapshot();
  }

  function scheduleMidnightRefresh() {
    if (midnightTimer) clearTimeout(midnightTimer);
    const now = new Date();
    const nextMidnight = new Date(now);
    nextMidnight.setHours(24, 0, 5, 0);
    midnightTimer = setTimeout(() => {
      void loadRuntimeData();
      scheduleMidnightRefresh();
    }, Math.max(1_000, nextMidnight.getTime() - now.getTime()));
  }

  function onDataChanged() { void loadRuntimeData(); }
  function onVisibilityChange() { if (document.visibilityState === 'visible') void loadRuntimeData(); }

  onMount(() => {
    void loadRuntimeData();
    scheduleMidnightRefresh();
    window.addEventListener('luma-data-changed', onDataChanged);
    document.addEventListener('visibilitychange', onVisibilityChange);
  });
  onDestroy(() => {
    window.removeEventListener('luma-data-changed', onDataChanged);
    document.removeEventListener('visibilitychange', onVisibilityChange);
    if (midnightTimer) clearTimeout(midnightTimer);
  });

  $: cssVars = `--glass-blur:${glass.blur}px;--glass-opacity:${glass.opacity / 100};--glass-sat:${glass.saturation}%;--glass-highlight:${glass.highlight / 100};--glass-refraction:${glass.refraction / 100};--glass-noise:${glass.noise / 100}`;
</script>

<div class="app-shell" style={cssVars} class:motion={glass.motion} class:runtime-ready={runtimeReady}>
  <div class="ambient ambient-a"></div><div class="ambient ambient-b"></div>
  <aside class="sidebar glass-panel">
    <button class="brand" aria-label="返回今日" on:click={() => (page = 'today')}><span class="brand-mark"><Sparkles size={20} strokeWidth={1.8} /></span><span class="brand-copy"><b>Luma</b><small>Schedule</small></span></button>
    <nav class="desktop-nav">{#each nav as item}<button class:active={page === item.id} on:click={() => (page = item.id)}><svelte:component this={item.icon} size={20} strokeWidth={1.75} /><span>{item.label}</span></button>{/each}</nav>
    <div class="sidebar-status"><span class="status-dot"></span><div><b>{runtimeReady ? (liveData ? '本地数据库' : '离线模式') : '正在载入'}</b><small>{liveData ? 'SQLite 已就绪' : '等待本地数据'}</small></div></div>
  </aside>
  <main class="main-stage">
    {#if page === 'today'}
      <Today courses={runtimeCourses} hasSchedule={scheduleSnapshot.hasSchedule} currentWeek={scheduleSnapshot.currentWeek} termName={scheduleSnapshot.termName} />
    {:else if page === 'week'}
      <Week courses={runtimeCourses} hasSchedule={scheduleSnapshot.hasSchedule} currentWeek={scheduleSnapshot.currentWeek} termName={scheduleSnapshot.termName} on:changed={loadRuntimeData} />
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
