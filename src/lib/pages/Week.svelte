<script lang="ts">
  import { ChevronLeft, ChevronRight } from 'lucide-svelte';
  import { afterUpdate, createEventDispatcher, onMount, tick } from 'svelte';
  import WeekCore from './WeekCore.svelte';
  import { getFullScheduleSnapshot } from '../weekSchedule';
  import type { Course, SchedulePreferences, WeekendMode } from '../types';

  export let courses: Course[];
  export let hasSchedule = false;
  export let currentWeek: number | null | undefined = null;
  export let termName: string | null | undefined = null;
  export let preferences: SchedulePreferences;

  const dispatch = createEventDispatcher<{ changed: void }>();
  let root: HTMLDivElement;
  let controls: HTMLDivElement;
  let allCourses: Course[] = courses;
  let fullHasSchedule = hasSchedule;
  let fullTermName = termName || preferences.termName || '';
  let fullTermStart = preferences.termStart || '';
  let fullWeekCount = preferences.weekCount || 20;
  let selectedWeek = currentWeek || 1;
  let touched = false;
  let loaded = false;
  let pointerId: number | null = null;
  let startX = 0;
  let startY = 0;
  let patchQueued = false;

  function clampWeek(value: number) {
    return Math.max(1, Math.min(Math.max(1, fullWeekCount), Math.trunc(value || 1)));
  }

  function selectWeek(value: number) {
    const next = clampWeek(value);
    if (next === selectedWeek) return;
    selectedWeek = next;
    touched = true;
  }

  function parseDate(raw: string) {
    const match = raw.trim().match(/^(\d{4})-(\d{2})-(\d{2})/);
    if (!match) return null;
    const value = new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]), 12);
    return Number.isNaN(value.getTime()) ? null : value;
  }

  function mondayOf(date: Date) {
    const copy = new Date(date);
    copy.setHours(12, 0, 0, 0);
    copy.setDate(copy.getDate() - ((copy.getDay() + 6) % 7));
    return copy;
  }

  function weekMonday() {
    const termStart = parseDate(fullTermStart || preferences.termStart || '');
    if (termStart) {
      const base = mondayOf(termStart);
      base.setDate(base.getDate() + (selectedWeek - 1) * 7);
      return base;
    }
    const base = mondayOf(new Date());
    if (currentWeek && currentWeek > 0) base.setDate(base.getDate() + (selectedWeek - currentWeek) * 7);
    return base;
  }

  function weekendMode(): WeekendMode {
    return preferences.weekendMode || (preferences.showWeekend === false ? 'weekdays' : 'auto');
  }

  function visibleDayNumbers(items: Course[]) {
    const included = new Set([1, 2, 3, 4, 5]);
    const mode = weekendMode();
    if (mode === 'sat' || mode === 'both') included.add(6);
    if (mode === 'sun' || mode === 'both') included.add(7);
    if (mode === 'auto') {
      if (items.some((course) => course.day === 6)) included.add(6);
      if (items.some((course) => course.day === 7)) included.add(7);
    }
    const order = preferences.weekStartsOn === 7 ? [7, 1, 2, 3, 4, 5, 6] : [1, 2, 3, 4, 5, 6, 7];
    return order.filter((day) => included.has(day));
  }

  function dateForDay(day: number) {
    const monday = weekMonday();
    const date = new Date(monday);
    date.setDate(monday.getDate() + (day - 1));
    return date;
  }

  function rangeLabel(items: Course[]) {
    const days = visibleDayNumbers(items);
    if (!days.length) return '';
    const first = dateForDay(days[0]);
    const last = dateForDay(days[days.length - 1]);
    return `${first.getMonth() + 1}月${first.getDate()}日 – ${last.getMonth() + 1}月${last.getDate()}日`;
  }

  async function reload() {
    try {
      const snapshot = await getFullScheduleSnapshot();
      allCourses = snapshot.courses;
      fullHasSchedule = snapshot.hasSchedule;
      fullTermName = snapshot.termName || termName || preferences.termName || '';
      fullTermStart = snapshot.termStart || preferences.termStart || '';
      fullWeekCount = snapshot.weekCount || preferences.weekCount || 20;
      selectedWeek = clampWeek(touched ? selectedWeek : (currentWeek || 1));
      loaded = true;
    } catch {
      allCourses = courses;
      fullHasSchedule = hasSchedule;
      fullTermName = termName || preferences.termName || '';
      fullTermStart = preferences.termStart || '';
      fullWeekCount = preferences.weekCount || 20;
    }
    queuePatch();
  }

  function queuePatch() {
    if (patchQueued) return;
    patchQueued = true;
    void tick().then(() => {
      patchQueued = false;
      patchCore();
    });
  }

  function patchCore() {
    if (!root) return;
    const topbar = root.querySelector<HTMLElement>('.week-topbar');
    if (topbar && controls && controls.previousElementSibling !== topbar) topbar.after(controls);
    const items = weekCourses;
    const eyebrow = root.querySelector<HTMLElement>('.week-topbar .eyebrow');
    if (eyebrow) eyebrow.textContent = rangeLabel(items);
    const subtitle = root.querySelector<HTMLElement>('.week-topbar p');
    if (subtitle) subtitle.textContent = [fullTermName, selectedWeek === currentWeek ? '本周' : '', items.length ? `${items.length} 个课程时段` : `第 ${selectedWeek} 周暂无课程`].filter(Boolean).join(' · ');

    const days = visibleDayNumbers(items);
    const headers = root.querySelectorAll<HTMLElement>('.week-header > div:not(.corner)');
    headers.forEach((header, index) => {
      const day = days[index];
      const date = day ? dateForDay(day) : null;
      const number = header.querySelector<HTMLElement>('b');
      if (number && date) number.textContent = String(date.getDate()).padStart(2, '0');
      header.classList.toggle('today', Boolean(selectedWeek === currentWeek && day === (((new Date()).getDay() + 6) % 7) + 1));
    });
    const empty = root.querySelector<HTMLElement>('.week-empty');
    if (empty && fullHasSchedule && !items.length) empty.textContent = `第 ${selectedWeek} 周没有课程。可以左右滑动查看其它周。`;
  }

  function onSelect(event: Event) {
    selectWeek(Number((event.currentTarget as HTMLSelectElement).value));
    queuePatch();
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
    if (Math.abs(dx) < 64 || Math.abs(dx) < Math.abs(dy) * 1.25) return;
    selectWeek(selectedWeek + (dx < 0 ? 1 : -1));
    queuePatch();
  }

  function coreChanged() {
    void reload();
    dispatch('changed');
  }

  onMount(() => { void reload(); });
  afterUpdate(queuePatch);

  $: if (!touched && currentWeek && currentWeek > 0 && selectedWeek !== currentWeek) selectedWeek = clampWeek(currentWeek);
  $: weekCourses = allCourses.filter((course) => !course.weeks?.length || course.weeks.includes(selectedWeek));
  $: weekOptions = Array.from({ length: Math.max(1, fullWeekCount) }, (_, index) => index + 1);
</script>

<div class="week-pager" bind:this={root} on:pointerdown={beginSwipe} on:pointerup={endSwipe}>
  <WeekCore
    courses={weekCourses}
    hasSchedule={fullHasSchedule}
    currentWeek={selectedWeek}
    termName={fullTermName}
    {preferences}
    on:changed={coreChanged}
  />
  {#if fullHasSchedule}
    <div class="week-switcher glass-panel" bind:this={controls} aria-label="切换教学周">
      <button on:click={() => selectWeek(selectedWeek - 1)} disabled={selectedWeek <= 1} aria-label="上一周"><ChevronLeft size={18} /></button>
      <label class="week-jump">
        <span>第 {selectedWeek} / {fullWeekCount} 周</span>
        <small>{loaded ? '左右滑动课表也可以切周' : '正在载入整学期课表…'}</small>
        <select value={selectedWeek} on:change={onSelect} aria-label="跳转到指定周">
          {#each weekOptions as week}<option value={week}>第 {week} 周</option>{/each}
        </select>
      </label>
      <button on:click={() => selectWeek(selectedWeek + 1)} disabled={selectedWeek >= fullWeekCount} aria-label="下一周"><ChevronRight size={18} /></button>
      {#if currentWeek && selectedWeek !== currentWeek}<button class="back-current" on:click={() => selectWeek(currentWeek || 1)}>本周</button>{/if}
    </div>
  {/if}
</div>

<style>
  .week-pager { display: contents; }
  .week-switcher { min-height: 48px; margin: -8px 0 12px; padding: 5px; border-radius: 18px; display: grid; grid-template-columns: 38px minmax(0,1fr) 38px auto; align-items: center; gap: 4px; }
  .week-switcher > button { width: 38px; height: 38px; border: 0; border-radius: 13px; display: grid; place-items: center; background: rgba(118,118,128,.07); color: #5751c9; }
  .week-switcher > button:disabled { opacity: .28; }
  .week-switcher .back-current { width: auto; padding: 0 10px; font-size: 11px; font-weight: 650; white-space: nowrap; }
  .week-jump { position: relative; min-width: 0; display: flex; flex-direction: column; justify-content: center; padding: 0 8px; cursor: pointer; }
  .week-jump span { font-size: 12px; font-weight: 680; color: #23232a; }
  .week-jump small { margin-top: 1px; font-size: 8px; color: rgba(60,60,67,.48); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .week-jump select { position: absolute; inset: 0; width: 100%; height: 100%; opacity: 0; cursor: pointer; }
  :global(.page-week .week-board) { touch-action: pan-y; }
  @media (max-width:760px) { .week-switcher { margin-top:-6px; } }
</style>
