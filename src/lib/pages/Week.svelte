<script lang="ts">
  import { CalendarDays, ChevronLeft, ChevronRight, Settings2 } from 'lucide-svelte';
  import { createEventDispatcher, onMount } from 'svelte';
  import WeekCore from './WeekCore.svelte';
  import { getFullScheduleSnapshot } from '../weekSchedule';
  import type { Course, SchedulePreferences } from '../types';

  export let courses: Course[];
  export let hasSchedule = false;
  export let currentWeek: number | null | undefined = null;
  export let termName: string | null | undefined = null;
  export let preferences: SchedulePreferences;

  const dispatch = createEventDispatcher<{ changed: void; openSettings: void }>();
  let allCourses: Course[] = courses;
  let fullHasSchedule = hasSchedule;
  let fullTermName = termName || preferences.termName || '';
  let fullTermStart = preferences.termStart || '';
  let fullWeekCount = Math.max(1, preferences.weekCount || 20);
  let selectedWeek = 1;
  let loaded = false;
  let pointerId: number | null = null;
  let startX = 0;
  let startY = 0;

  function parseDate(raw: string) {
    const match = raw.trim().match(/^(\d{4})-(\d{2})-(\d{2})/);
    if (!match) return null;
    const value = new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]), 12);
    return Number.isNaN(value.getTime()) ? null : value;
  }

  function startOfWeek(date: Date) {
    const copy = new Date(date);
    copy.setHours(12, 0, 0, 0);
    const jsDay = copy.getDay();
    const offset = preferences.weekStartsOn === 7 ? jsDay : (jsDay + 6) % 7;
    copy.setDate(copy.getDate() - offset);
    return copy;
  }

  function clampWeek(value: number) {
    return Math.max(1, Math.min(fullWeekCount, Math.trunc(value || 1)));
  }

  function derivedCurrentWeek() {
    if (currentWeek && currentWeek > 0) return clampWeek(currentWeek);
    const termStart = parseDate(fullTermStart || preferences.termStart || '');
    if (!termStart) return 1;
    const base = startOfWeek(termStart);
    const today = startOfWeek(new Date());
    const diff = Math.floor((today.getTime() - base.getTime()) / 604800000) + 1;
    return clampWeek(diff);
  }

  function weekStart(week: number) {
    const termStart = parseDate(fullTermStart || preferences.termStart || '');
    if (termStart) {
      const base = startOfWeek(termStart);
      base.setDate(base.getDate() + (week - 1) * 7);
      return base;
    }
    const base = startOfWeek(new Date());
    const reference = derivedCurrentWeek();
    base.setDate(base.getDate() + (week - reference) * 7);
    return base;
  }

  function selectWeek(value: number) {
    selectedWeek = clampWeek(value);
  }

  function shortDate(date: Date) {
    return `${date.getMonth() + 1}.${date.getDate()}`;
  }

  function rangeLabel(week: number) {
    const start = weekStart(week);
    const end = new Date(start);
    end.setDate(start.getDate() + 6);
    return `${start.getMonth() + 1}月${start.getDate()}日 – ${end.getMonth() + 1}月${end.getDate()}日`;
  }

  function beginSwipe(event: PointerEvent) {
    if (event.pointerType === 'mouse') return;
    pointerId = event.pointerId;
    startX = event.clientX;
    startY = event.clientY;
  }

  function endSwipe(event: PointerEvent) {
    if (event.pointerId !== pointerId) return;
    pointerId = null;
    const dx = event.clientX - startX;
    const dy = event.clientY - startY;
    if (Math.abs(dx) < 68 || Math.abs(dx) < Math.abs(dy) * 1.2) return;
    selectWeek(selectedWeek + (dx < 0 ? 1 : -1));
  }

  async function reload() {
    try {
      const snapshot = await getFullScheduleSnapshot();
      allCourses = snapshot.courses;
      fullHasSchedule = snapshot.hasSchedule;
      fullTermName = snapshot.termName || termName || preferences.termName || '';
      fullTermStart = snapshot.termStart || preferences.termStart || '';
      fullWeekCount = Math.max(1, snapshot.weekCount || preferences.weekCount || 20);
    } catch {
      allCourses = courses;
      fullHasSchedule = hasSchedule;
      fullTermName = termName || preferences.termName || '';
      fullTermStart = preferences.termStart || '';
      fullWeekCount = Math.max(1, preferences.weekCount || 20);
    }
    selectedWeek = derivedCurrentWeek();
    loaded = true;
  }

  function coreChanged() {
    void reload();
    dispatch('changed');
  }

  onMount(() => { void reload(); });

  $: weekCourses = allCourses.filter((course) => !course.weeks?.length || course.weeks.includes(selectedWeek));
  $: currentTeachingWeek = derivedCurrentWeek();
  $: weekOptions = Array.from({ length: fullWeekCount }, (_, index) => index + 1);
  $: chipWeeks = weekOptions.filter((week) => Math.abs(week - selectedWeek) <= 2);
  $: selectedWeekStart = weekStart(selectedWeek);
</script>

<section class="page page-week week-shell">
  <header class="week-page-head">
    <div class="week-heading">
      <span class="eyebrow">{rangeLabel(selectedWeek)}</span>
      <div class="week-title-line"><h1>第 {selectedWeek} 周</h1>{#if selectedWeek === currentTeachingWeek}<span>本周</span>{/if}</div>
      <p>{fullTermName || (fullTermStart ? '当前学期' : '尚未设置学期')} · {weekCourses.length ? `${weekCourses.length} 个课程时段` : '暂无课程'}</p>
    </div>
    <div class="week-stepper glass-panel" aria-label="切换教学周">
      <button on:click={() => selectWeek(selectedWeek - 1)} disabled={selectedWeek <= 1} aria-label="上一周"><ChevronLeft size={19}/></button>
      <label>
        <b>第 {selectedWeek} 周</b>
        <small>{shortDate(selectedWeekStart)}</small>
        <select value={selectedWeek} on:change={(event) => selectWeek(Number(event.currentTarget.value))} aria-label="跳转到指定周">
          {#each weekOptions as week}<option value={week}>第 {week} 周</option>{/each}
        </select>
      </label>
      <button on:click={() => selectWeek(selectedWeek + 1)} disabled={selectedWeek >= fullWeekCount} aria-label="下一周"><ChevronRight size={19}/></button>
    </div>
  </header>

  <div class="week-strip glass-panel" aria-label="附近教学周">
    {#each chipWeeks as week}
      <button class:active={week === selectedWeek} class:current={week === currentTeachingWeek} on:click={() => selectWeek(week)}>
        <span>第 {week} 周</span><small>{shortDate(weekStart(week))}</small>
      </button>
    {/each}
    {#if selectedWeek !== currentTeachingWeek}<button class="back-current" on:click={() => selectWeek(currentTeachingWeek)}><CalendarDays size={14}/> 回本周</button>{/if}
  </div>

  {#if !fullTermStart}
    <button class="term-start-hint content-surface" on:click={() => dispatch('openSettings')}>
      <Settings2 size={17}/><span><b>设置开学日期</b><small>设置后会自动计算当前教学周，并让每周日期准确对应。</small></span><ChevronRight size={17}/>
    </button>
  {/if}

  <div class="week-board-swipe" on:pointerdown={beginSwipe} on:pointerup={endSwipe}>
    <WeekCore
      courses={weekCourses}
      hasSchedule={fullHasSchedule}
      currentWeek={selectedWeek}
      termName={fullTermName}
      displayDate={selectedWeekStart}
      showTopbar={false}
      {preferences}
      on:changed={coreChanged}
    />
  </div>

  {#if !loaded}<div class="week-loading">正在载入整学期课表…</div>{/if}
</section>

<style>
  .week-shell { display:grid; gap:12px; }
  .week-page-head { display:flex; align-items:flex-end; justify-content:space-between; gap:16px; }
  .week-heading { min-width:0; }
  .week-heading .eyebrow { display:block; color:rgba(60,60,67,.56); font-size:12px; margin-bottom:4px; }
  .week-title-line { display:flex; align-items:center; gap:9px; }
  .week-title-line h1 { margin:0; font-size:38px; line-height:1; letter-spacing:-.055em; }
  .week-title-line span { padding:4px 8px; border-radius:999px; background:rgba(91,86,214,.11); color:#5751c9; font-size:10px; font-weight:700; }
  .week-heading p { margin:7px 0 0; color:rgba(60,60,67,.54); font-size:12px; }
  .week-stepper { flex:none; min-height:52px; padding:5px; border-radius:18px; display:grid; grid-template-columns:40px minmax(82px,1fr) 40px; align-items:center; gap:4px; }
  .week-stepper > button { width:40px; height:40px; border:0; border-radius:13px; display:grid; place-items:center; background:rgba(118,118,128,.07); color:#5751c9; }
  .week-stepper > button:disabled { opacity:.25; }
  .week-stepper label { position:relative; min-width:84px; text-align:center; display:grid; gap:1px; }
  .week-stepper b { font-size:12px; }
  .week-stepper small { font-size:9px; color:rgba(60,60,67,.48); }
  .week-stepper select { position:absolute; inset:0; opacity:0; width:100%; height:100%; }
  .week-strip { display:flex; gap:6px; padding:6px; border-radius:18px; overflow-x:auto; scrollbar-width:none; }
  .week-strip::-webkit-scrollbar { display:none; }
  .week-strip button { flex:0 0 auto; min-width:70px; min-height:44px; border:0; border-radius:13px; background:transparent; color:inherit; display:grid; place-content:center; gap:1px; }
  .week-strip button span { font-size:10px; font-weight:650; }
  .week-strip button small { font-size:8px; color:rgba(60,60,67,.48); }
  .week-strip button.active { background:rgba(91,86,214,.12); color:#514bd0; }
  .week-strip button.current:not(.active) { box-shadow:inset 0 0 0 1px rgba(91,86,214,.20); }
  .week-strip .back-current { margin-left:auto; min-width:auto; padding:0 10px; display:flex; align-items:center; gap:5px; color:#5751c9; }
  .term-start-hint { width:100%; min-height:58px; border:0; border-radius:18px; padding:10px 14px; display:grid; grid-template-columns:auto 1fr auto; align-items:center; gap:10px; color:inherit; text-align:left; }
  .term-start-hint > span { display:grid; gap:2px; }
  .term-start-hint b { font-size:11px; }
  .term-start-hint small { font-size:9px; line-height:1.45; color:rgba(60,60,67,.52); }
  .term-start-hint > svg:first-child { color:#5b56d6; }
  .term-start-hint > svg:last-child { color:rgba(60,60,67,.35); }
  .week-board-swipe { touch-action:pan-y; }
  .week-loading { text-align:center; color:rgba(60,60,67,.45); font-size:10px; }
  @media (max-width:760px) {
    .week-shell { gap:10px; }
    .week-page-head { align-items:center; gap:10px; }
    .week-title-line h1 { font-size:34px; }
    .week-heading p { max-width:220px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
    .week-stepper { min-height:48px; grid-template-columns:36px 68px 36px; border-radius:17px; }
    .week-stepper > button { width:36px; height:36px; }
    .week-stepper label { min-width:68px; }
    .week-strip { margin-top:-2px; }
  }
</style>
