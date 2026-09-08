<script lang="ts">
  import { ChevronLeft, ChevronRight } from 'lucide-svelte';
  import { createEventDispatcher, onMount } from 'svelte';
  import WeekCore from './WeekCore.svelte';
  import { getFullScheduleSnapshot } from '../weekSchedule';
  import type { Course, SchedulePreferences } from '../types';

  export let courses: Course[];
  export let hasSchedule = false;
  export let currentWeek: number | null | undefined = null;
  export let termName: string | null | undefined = null;
  export let preferences: SchedulePreferences;

  const dispatch = createEventDispatcher<{ changed: void }>();
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

  function compactRangeLabel(week: number) {
    const start = weekStart(week);
    const end = new Date(start);
    end.setDate(start.getDate() + 6);
    return `${start.getMonth() + 1}.${start.getDate()}–${end.getMonth() + 1}.${end.getDate()}`;
  }

  function beginSwipe(event: PointerEvent) {
    if (event.pointerType === 'mouse') return;
    pointerId = event.pointerId;
    startX = event.clientX;
    startY = event.clientY;
  }

  function cancelSwipe() {
    pointerId = null;
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
  $: selectedWeekStart = weekStart(selectedWeek);
</script>

<section class="page page-week week-shell">
  <div class="week-toolbar glass-panel" aria-label="教学周导航">
    <button
      class="week-arrow"
      on:click={() => selectWeek(selectedWeek - 1)}
      disabled={selectedWeek <= 1}
      aria-label="上一周"
    >
      <ChevronLeft size={21}/>
    </button>

    <label class="week-selector">
      <span class="week-title">
        <b>第 {selectedWeek} 周</b>
        {#if selectedWeek === currentTeachingWeek}<em>本周</em>{/if}
      </span>
      <span class="week-date">{compactRangeLabel(selectedWeek)} · 点击跳周</span>
      <select
        value={selectedWeek}
        on:change={(event) => selectWeek(Number(event.currentTarget.value))}
        aria-label="直接跳转到指定周"
      >
        {#each weekOptions as week}
          <option value={week}>第 {week} 周 · {shortDate(weekStart(week))}</option>
        {/each}
      </select>
    </label>

    <button
      class="current-week"
      class:active={selectedWeek === currentTeachingWeek}
      on:click={() => selectWeek(currentTeachingWeek)}
      disabled={selectedWeek === currentTeachingWeek}
      aria-label="回到本周"
    >本周</button>

    <button
      class="week-arrow"
      on:click={() => selectWeek(selectedWeek + 1)}
      disabled={selectedWeek >= fullWeekCount}
      aria-label="下一周"
    >
      <ChevronRight size={21}/>
    </button>
  </div>

  <div class="week-board-swipe" on:pointerdown={beginSwipe} on:pointerup={endSwipe} on:pointercancel={cancelSwipe}>
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
  .week-shell {
    display:grid;
    gap:4px;
  }

  .week-toolbar {
    display:grid;
    grid-template-columns:38px minmax(0, 1fr) 42px 38px;
    align-items:center;
    gap:4px;
    min-height:52px;
    padding:5px 7px;
    border-radius:18px;
  }

  .week-arrow {
    width:38px;
    height:42px;
    border:0;
    border-radius:13px;
    display:grid;
    place-items:center;
    background:transparent;
    color:#5751c9;
  }

  .week-arrow:disabled {
    opacity:.24;
  }

  .week-selector {
    position:relative;
    min-width:0;
    height:42px;
    display:grid;
    align-content:center;
    gap:1px;
    padding:0 8px;
    border-radius:13px;
    background:rgba(91,86,214,.08);
    cursor:pointer;
  }

  .week-title {
    min-width:0;
    display:flex;
    align-items:center;
    gap:6px;
  }

  .week-title b {
    font-size:16px;
    line-height:1.05;
    letter-spacing:-.03em;
    white-space:nowrap;
  }

  .week-title em {
    padding:2px 5px;
    border-radius:999px;
    background:rgba(91,86,214,.13);
    color:#5751c9;
    font-size:7px;
    font-style:normal;
    font-weight:750;
    white-space:nowrap;
  }

  .week-date {
    color:rgba(60,60,67,.47);
    font-size:8px;
    line-height:1.2;
    white-space:nowrap;
    overflow:hidden;
    text-overflow:ellipsis;
  }

  .week-selector select {
    position:absolute;
    inset:0;
    width:100%;
    height:100%;
    opacity:0;
    cursor:pointer;
  }

  .current-week {
    width:42px;
    height:32px;
    border:0;
    border-radius:999px;
    background:rgba(91,86,214,.10);
    color:#5751c9;
    font-size:9px;
    font-weight:700;
  }

  .current-week.active,
  .current-week:disabled {
    opacity:.38;
  }

  .week-board-swipe {
    min-height:0;
    touch-action:pan-y;
  }

  .week-loading {
    text-align:center;
    color:rgba(60,60,67,.45);
    font-size:10px;
  }

  @media (max-width:760px) {
    .week-board-swipe {
      margin-inline:-8px;
    }

    .week-toolbar {
      grid-template-columns:34px minmax(0, 1fr) 38px 34px;
      gap:3px;
      min-height:48px;
      padding:4px 6px;
      border-radius:16px;
    }

    .week-arrow {
      width:34px;
      height:38px;
      border-radius:12px;
    }

    .week-selector {
      height:38px;
      padding:0 7px;
      border-radius:12px;
    }

    .week-title b {
      font-size:15px;
    }

    .week-date {
      font-size:7.5px;
    }

    .current-week {
      width:38px;
      height:30px;
      font-size:8px;
    }
  }
</style>
