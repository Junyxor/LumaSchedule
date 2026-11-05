<script lang="ts">
  import { CalendarPlus, ChevronLeft, ChevronRight, Download, Plus } from 'lucide-svelte';
  import { untrack } from 'svelte';
  import { fly } from 'svelte/transition';
  import { app } from '../state/app.svelte';
  import {
    clamp,
    courseInWeek,
    courseRange,
    dateOfWeekDay,
    formatClock,
    layoutLanes,
    minutesOfDay,
    monthDayLabel,
    sameDay,
    visibleWeekdays,
    WEEKDAY_NARROW,
    WEEKDAY_SHORT
  } from '../schedule/calendar';
  import { courseSheets, toneClass } from '../schedule/courseSheets.svelte';
  import EmptyState from '../ui/EmptyState.svelte';
  import Sheet from '../ui/Sheet.svelte';

  const ctx = $derived(app.ctx);
  let selectedWeek = $state(untrack(() => app.ctx.currentWeek));
  /** Until the user picks a week, keep showing the current one (data may still be loading). */
  let followCurrent = true;
  let direction = $state(0);
  let pickerOpen = $state(false);
  let ghost = $state<{ day: number; startSection: number; endSection: number; top: number; height: number } | null>(null);

  $effect(() => {
    const current = ctx.currentWeek;
    if (followCurrent) selectedWeek = current;
  });

  $effect(() => {
    if (selectedWeek > ctx.weekCount) selectedWeek = ctx.weekCount;
  });

  const isCurrentWeek = $derived((ctx.status === 'active' && ctx.rawWeek === selectedWeek) || (ctx.status === 'unset' && selectedWeek === 1));
  const canReturn = $derived((ctx.status === 'active' || ctx.status === 'unset') && !isCurrentWeek);
  const days = $derived(visibleWeekdays(app.display.weekendMode, ctx.weekStartsOn, app.semester.courses));
  const columns = $derived(days.map((day) => ({ day, date: dateOfWeekDay(ctx, selectedWeek, day, app.now) })));
  const firstDate = $derived(columns[0]?.date ?? app.now);
  const lastDate = $derived(columns[columns.length - 1]?.date ?? app.now);
  const weekCourses = $derived(app.semester.courses.filter((course) => courseInWeek(course, selectedWeek)));
  const colorful = $derived(app.appearance.colorfulCourses);
  const compact = $derived(app.display.compactMode);

  /** Pixels per minute of the time-proportional grid. */
  const ppm = $derived(compact ? 0.74 : 1);
  const allRanges = $derived(app.semester.courses.map((course) => courseRange(course, app.sections)));
  const dayStart = $derived(Math.floor(Math.min(app.sections[0]?.start ?? 8 * 60, ...allRanges.map((range) => range.start)) / 30) * 30);
  const dayEnd = $derived(
    Math.ceil(Math.max(app.sections[app.sections.length - 1]?.end ?? 22 * 60, ...allRanges.map((range) => range.end)) / 30) * 30
  );
  const gridHeight = $derived((dayEnd - dayStart) * ppm);
  const y = (minute: number) => (minute - dayStart) * ppm;

  const placed = $derived(
    new Map(
      days.map((day) => [
        day,
        layoutLanes(weekCourses.filter((course) => course.day === day).map((course) => ({ item: course, ...courseRange(course, app.sections) })))
      ])
    )
  );

  const nowLine = $derived.by(() => {
    if (!isCurrentWeek) return null;
    const minute = minutesOfDay(app.now);
    if (minute < dayStart || minute > dayEnd) return null;
    const column = columns.find((item) => sameDay(item.date, app.now));
    return column ? { day: column.day, top: y(minute) } : null;
  });

  function goTo(week: number) {
    const next = clamp(week, 1, ctx.weekCount);
    if (next === selectedWeek) return;
    followCurrent = next === ctx.currentWeek;
    direction = next > selectedWeek ? 1 : -1;
    selectedWeek = next;
    ghost = null;
  }

  let swipe: { id: number; x: number; y: number } | null = null;

  function pointerDown(event: PointerEvent) {
    if (event.pointerType === 'mouse') return;
    swipe = { id: event.pointerId, x: event.clientX, y: event.clientY };
  }

  function pointerUp(event: PointerEvent) {
    if (!swipe || event.pointerId !== swipe.id) return;
    const dx = event.clientX - swipe.x;
    const dy = event.clientY - swipe.y;
    swipe = null;
    if (Math.abs(dx) > 64 && Math.abs(dx) > Math.abs(dy) * 1.6) goTo(selectedWeek + (dx < 0 ? 1 : -1));
  }

  function busy(day: number, start: number, end: number) {
    return (placed.get(day) ?? []).some((block) => block.start < end && block.end > start);
  }

  function tapEmpty(day: number, event: MouseEvent) {
    const rect = (event.currentTarget as HTMLElement).getBoundingClientRect();
    const minute = dayStart + (event.clientY - rect.top) / ppm;
    const section = app.sections.find((item) => minute < item.end) ?? app.sections[app.sections.length - 1];
    if (!section || busy(day, section.start, section.end)) {
      ghost = null;
      return;
    }
    // Suggest a two-section block unless the next section is already taken.
    const next = app.sections[section.section];
    const endSection = next && !busy(day, next.start, next.end) ? next.section : section.section;
    const last = app.sections[endSection - 1];
    ghost = { day, startSection: section.section, endSection, top: y(section.start), height: (last.end - section.start) * ppm };
  }

  function addFromGhost() {
    if (!ghost) return;
    courseSheets.create({ day: ghost.day, startSection: ghost.startSection, endSection: ghost.endSection });
    ghost = null;
  }
</script>

<section class="week-page">
  <header class="toolbar">
    <button class="icon-btn" type="button" onclick={() => goTo(selectedWeek - 1)} disabled={selectedWeek <= 1} aria-label="上一周">
      <ChevronLeft size={22} />
    </button>
    <button class="week-title" type="button" onclick={() => (pickerOpen = true)} aria-label="选择周次">
      <b>第 {selectedWeek} 周{#if isCurrentWeek}<span class="badge accent">本周</span>{/if}</b>
      <span class="num">{monthDayLabel(firstDate)} – {monthDayLabel(lastDate)}</span>
    </button>
    <button class="icon-btn" type="button" onclick={() => goTo(selectedWeek + 1)} disabled={selectedWeek >= ctx.weekCount} aria-label="下一周">
      <ChevronRight size={22} />
    </button>
    <span class="spacer"></span>
    {#if canReturn}
      <button class="btn btn-sm btn-tonal" type="button" onclick={() => goTo(ctx.currentWeek)}>回到本周</button>
    {/if}
    <button class="icon-btn accent" type="button" onclick={() => courseSheets.create()} aria-label="添加课程"><Plus size={21} /></button>
  </header>

  <div class="board" class:compact style="--cols:{columns.length}">
    <div class="head">
      <div class="corner num">{firstDate.getMonth() + 1}<small>月</small></div>
      {#each columns as column (column.day)}
        <div class="day" class:today={sameDay(column.date, app.now)}>
          <span>{WEEKDAY_NARROW[column.day - 1]}</span>
          <b class="num">{column.date.getDate()}</b>
        </div>
      {/each}
    </div>

    <div class="scroll" onpointerdown={pointerDown} onpointerup={pointerUp} onpointercancel={() => (swipe = null)} role="presentation">
      {#key selectedWeek}
        <div class="grid" style:height="{gridHeight}px" in:fly={{ x: direction * 28, duration: direction ? 180 : 0 }}>
          <div class="bands" aria-hidden="true">
            {#each app.sections as section (section.section)}
              <div class="band" style:top="{y(section.start)}px" style:height="{(section.end - section.start) * ppm}px"></div>
            {/each}
          </div>
          <div class="axis" aria-hidden="true">
            {#each app.sections as section (section.section)}
              <div class="axis-cell" style:top="{y(section.start)}px" style:height="{(section.end - section.start) * ppm}px">
                <b>{section.section}</b>
                {#if app.display.showTime}<span class="num">{formatClock(section.start)}</span>{/if}
              </div>
            {/each}
          </div>
          {#each columns as column (column.day)}
            <div class="col" class:today={sameDay(column.date, app.now)}>
              <button class="hit" type="button" tabindex="-1" aria-label="在{WEEKDAY_SHORT[column.day - 1]}添加课程" onclick={(event) => tapEmpty(column.day, event)}></button>
              {#each placed.get(column.day) ?? [] as block (block.item.id)}
                {@const course = block.item}
                {@const height = Math.max(22, (block.end - block.start) * ppm - 2)}
                <button
                  class="course {toneClass(course, colorful)}"
                  type="button"
                  style:top="{y(block.start) + 1}px"
                  style:height="{height}px"
                  style:left="calc({(block.lane / block.lanes) * 100}% + 1.5px)"
                  style:width="calc({100 / block.lanes}% - 3px)"
                  onclick={() => courseSheets.show(course, column.date)}
                >
                  <b>{course.name}</b>
                  {#if app.display.showRoom && course.room}<span class="room">{course.room}</span>{/if}
                  {#if app.display.showTime && height > 64}
                    <span class="time num"><span class="t-start">{formatClock(block.start)}</span><span class="t-end">–{formatClock(block.end)}</span></span>
                  {/if}
                  {#if app.display.showTeacher && course.teacher && !compact && height > 80}<span>{course.teacher}</span>{/if}
                </button>
              {/each}
              {#if ghost?.day === column.day}
                <button class="ghost" type="button" style:top="{ghost.top + 1}px" style:height="{ghost.height - 2}px" onclick={addFromGhost} aria-label="添加课程">
                  <Plus size={20} />
                </button>
              {/if}
              {#if nowLine?.day === column.day}<div class="now" style:top="{nowLine.top}px" aria-hidden="true"></div>{/if}
            </div>
          {/each}
        </div>
      {/key}

      {#if ctx.status === 'none' && app.ready}
        <div class="overlay">
          <div class="card">
            <EmptyState icon={CalendarPlus} title="还没有课表" message="导入学校教务课表，或者点空白格子手动添加课程。">
              {#snippet actions()}
                <button class="btn btn-primary btn-sm" type="button" onclick={() => app.go('import')}><Download size={16} /> 导入课表</button>
                <button class="btn btn-sm" type="button" onclick={() => courseSheets.create()}><Plus size={16} /> 添加课程</button>
              {/snippet}
            </EmptyState>
          </div>
        </div>
      {:else if app.ready && !weekCourses.length}
        <div class="week-empty">第 {selectedWeek} 周没有课程</div>
      {/if}
    </div>
  </div>
</section>

<Sheet open={pickerOpen} title="选择周次" subtitle={ctx.status === 'active' ? `今天是第 ${ctx.rawWeek} 周` : ''} onclose={() => (pickerOpen = false)}>
  <div class="picker">
    {#each Array.from({ length: ctx.weekCount }, (_, index) => index + 1) as week (week)}
      <button
        type="button"
        class:selected={week === selectedWeek}
        class:current={(ctx.status === 'active' && ctx.rawWeek === week) || (ctx.status === 'unset' && week === 1)}
        onclick={() => {
          goTo(week);
          pickerOpen = false;
        }}
      >
        <b class="num">{week}</b>
        <span class="num">{monthDayLabel(dateOfWeekDay(ctx, week, ctx.weekStartsOn === 7 ? 7 : 1, app.now))}</span>
      </button>
    {/each}
  </div>
</Sheet>

<style>
  .week-page {
    display: flex;
    flex-direction: column;
    height: 100vh;
    height: 100dvh;
    padding: calc(var(--inset-top) + 6px) calc(var(--inset-right) + 8px) calc(var(--tabbar-height) + var(--inset-bottom) + 8px)
      calc(var(--inset-left) + 8px);
    animation: fade-in 0.16s ease-out;
  }

  .toolbar {
    display: flex;
    align-items: center;
    gap: 2px;
    min-height: 52px;
    padding: 0 0 6px;
  }

  .week-title {
    display: grid;
    gap: 1px;
    min-width: 0;
    padding: 4px 6px;
    border: 0;
    border-radius: var(--radius-sm);
    background: transparent;
    text-align: left;
  }

  .week-title:active {
    background: var(--fill);
  }

  .week-title b {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: var(--text-lg);
    font-weight: 700;
    line-height: 1.2;
    white-space: nowrap;
  }

  .week-title > span {
    color: var(--text-2);
    font-size: var(--text-xs);
    white-space: nowrap;
  }

  .spacer {
    flex: 1;
  }

  .toolbar .btn {
    margin-right: 4px;
  }

  .board {
    --axis: 34px;
    position: relative;
    display: flex;
    flex: 1;
    flex-direction: column;
    min-height: 0;
    overflow: hidden;
    border: 1px solid var(--border);
    border-radius: var(--radius-lg);
    background: var(--surface);
  }

  .head {
    display: grid;
    flex: none;
    grid-template-columns: var(--axis) repeat(var(--cols), minmax(0, 1fr));
    border-bottom: 1px solid var(--border);
    background: var(--surface);
  }

  .corner {
    display: grid;
    place-content: center;
    color: var(--text-2);
    font-size: var(--text-md);
    font-weight: 650;
    line-height: 1;
  }

  .corner small {
    display: block;
    margin-top: 2px;
    font-size: 10px;
    font-weight: 500;
    text-align: center;
  }

  .day {
    display: grid;
    justify-items: center;
    gap: 2px;
    padding: 6px 0 7px;
    color: var(--text-2);
  }

  .day span {
    font-size: var(--text-xs);
    font-weight: 550;
  }

  .day b {
    display: grid;
    place-items: center;
    width: 26px;
    height: 26px;
    border-radius: 50%;
    color: var(--text);
    font-size: var(--text-md);
    font-weight: 650;
  }

  .day.today span {
    color: var(--accent);
  }

  .day.today b {
    background: var(--accent);
    color: var(--on-accent);
  }

  .scroll {
    position: relative;
    flex: 1;
    min-height: 0;
    overflow-y: auto;
    overscroll-behavior: contain;
    touch-action: pan-y;
  }

  .grid {
    position: relative;
    display: grid;
    grid-template-columns: var(--axis) repeat(var(--cols), minmax(0, 1fr));
    min-height: 100%;
    margin-bottom: 8px;
  }

  .bands {
    position: absolute;
    inset: 0 0 0 var(--axis);
    pointer-events: none;
  }

  .band {
    position: absolute;
    right: 0;
    left: 0;
    border-top: 1px solid var(--border);
  }

  .band:nth-child(odd) {
    background: var(--surface-2);
  }

  .axis,
  .col {
    position: relative;
    height: 100%;
  }

  .axis-cell {
    position: absolute;
    right: 0;
    left: 0;
    display: grid;
    align-content: start;
    justify-items: center;
    padding-top: 4px;
    border-top: 1px solid var(--border);
    color: var(--text-2);
  }

  .axis-cell b {
    font-size: var(--text-sm);
    font-weight: 650;
    line-height: 1.2;
  }

  .axis-cell span {
    color: var(--text-3);
    font-size: 10px;
    line-height: 1.2;
  }

  .col + .col,
  .axis + .col {
    border-left: 1px solid var(--border);
  }

  .col.today {
    background: var(--accent-softer);
  }

  .hit {
    position: absolute;
    inset: 0;
    width: 100%;
    border: 0;
    background: transparent;
    cursor: default;
  }

  .course {
    position: absolute;
    z-index: 2;
    display: flex;
    flex-direction: column;
    gap: 2px;
    overflow: hidden;
    padding: 4px 4px 4px 6px;
    border: 0;
    border-radius: var(--radius-xs);
    background: var(--tone-bg);
    color: var(--tone-fg);
    text-align: left;
    box-shadow: inset 2.5px 0 0 var(--tone-bar);
  }

  .course:active {
    filter: brightness(0.96);
  }

  .course b {
    display: -webkit-box;
    overflow: hidden;
    font-size: 12px;
    font-weight: 650;
    line-height: 1.25;
    word-break: break-all;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 4;
    line-clamp: 4;
  }

  .course span {
    overflow: hidden;
    font-size: 10.5px;
    line-height: 1.25;
    opacity: 0.8;
    word-break: break-all;
  }

  .course .room {
    font-weight: 550;
    opacity: 0.9;
  }

  .course .time {
    white-space: nowrap;
  }

  .course .time span {
    opacity: 1;
  }

  /* Narrow phone columns only have room for the start time. */
  .course .t-end {
    display: none;
  }

  .compact .course b {
    font-size: 11px;
    -webkit-line-clamp: 3;
    line-clamp: 3;
  }

  .ghost {
    position: absolute;
    z-index: 3;
    right: 2px;
    left: 2px;
    display: grid;
    place-items: center;
    border: 1.5px dashed var(--accent-line);
    border-radius: var(--radius-xs);
    background: var(--accent-soft);
    color: var(--accent);
    animation: fade-in 0.15s ease-out;
  }

  .now {
    position: absolute;
    z-index: 4;
    right: 0;
    left: 0;
    height: 2px;
    margin-top: -1px;
    background: var(--danger);
    pointer-events: none;
  }

  .now::before {
    content: '';
    position: absolute;
    top: -3px;
    left: -4px;
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: var(--danger);
  }

  .overlay {
    position: absolute;
    inset: 0;
    z-index: 5;
    display: grid;
    place-items: center;
    padding: 20px;
    background: var(--scrim);
  }

  .overlay .card {
    width: min(100%, 360px);
    box-shadow: var(--shadow-md);
  }

  .week-empty {
    position: absolute;
    top: 16px;
    left: 50%;
    z-index: 5;
    padding: 6px 14px;
    border-radius: var(--radius-pill);
    background: var(--surface-raised);
    box-shadow: var(--shadow-md);
    color: var(--text-2);
    font-size: var(--text-sm);
    transform: translateX(-50%);
    pointer-events: none;
    white-space: nowrap;
  }

  .picker {
    display: grid;
    grid-template-columns: repeat(5, minmax(0, 1fr));
    gap: 8px;
  }

  .picker button {
    display: grid;
    justify-items: center;
    gap: 1px;
    padding: 9px 0 8px;
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    background: var(--surface-2);
  }

  .picker b {
    font-size: var(--text-base);
    font-weight: 650;
  }

  .picker span {
    color: var(--text-3);
    font-size: 10.5px;
  }

  .picker .current {
    border-color: var(--accent-line);
  }

  .picker .current b {
    color: var(--accent);
  }

  .picker .selected {
    border-color: var(--accent);
    background: var(--accent);
    color: var(--on-accent);
  }

  .picker .selected b,
  .picker .selected span {
    color: var(--on-accent);
  }

  @media (min-width: 600px) {
    .board {
      --axis: 44px;
    }

    .course b {
      font-size: 13px;
    }

    .course span {
      font-size: 11.5px;
    }

    .course .t-end {
      display: inline;
    }
  }
</style>
