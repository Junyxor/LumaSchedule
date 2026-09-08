<script lang="ts">
  import { Plus, Trash2, X } from 'lucide-svelte';
  import { createEventDispatcher, onDestroy, onMount } from 'svelte';
  import ConfirmSheet from '../components/ConfirmSheet.svelte';
  import { deleteScheduleCourse, saveScheduleCourse } from '../tauri';
  import type { Course, CourseMutation, SchedulePreferences, WeekendMode } from '../types';

  export let courses: Course[];
  export let timelineCourses: Course[] = [];
  export let hasSchedule = false;
  export let currentWeek: number | null | undefined = null;
  export let termName: string | null | undefined = null;
  export let displayDate: Date | null = null;
  export let showTopbar = true;
  export let addRequest = 0;
  export let preferences: SchedulePreferences = {
    hasSchedule: false,
    termName: '',
    termStart: '',
    weekCount: 20,
    timezone: 'Asia/Shanghai',
    weekStartsOn: 1,
    weekendMode: 'auto',
    showTeacher: true,
    showRoom: true,
    showTime: true,
    compactMode: false,
    defaultSections: 12
  };

  const TIME_STEP_MINUTES = 5;
  const TIMELINE_PADDING_MINUTES = 30;
  const MIN_TIMELINE_SPAN = 4 * 60;
  const DEFAULT_DAY_START = 8 * 60;
  const DEFAULT_SECTION_LENGTH = 50;
  const DEFAULT_SECTION_STRIDE = 60;
  const dispatch = createEventDispatcher<{ changed: void }>();
  const weekdayNames = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];
  const sectionOptions = Array.from({ length: 30 }, (_, index) => index + 1);

  let now = new Date();
  let timer: ReturnType<typeof setInterval> | null = null;
  let editorOpen = false;
  let editorBusy = false;
  let editorError = '';
  let deleteConfirmOpen = false;
  let weeksText = '1-20';
  let creditText = '';
  let draft: CourseMutation = blankDraft();
  let handledAddRequest = addRequest;

  let timeReferenceCourses: Course[] = [];
  let visibleDayNumbers: number[] = [];
  let visibleDays: Array<{ label: string; dayNumber: number; date: string; month: number; fullDate: Date }> = [];
  let visibleCourses: Course[] = [];
  let dayCount = 5;
  let sectionCount = 12;
  let sections: number[] = [];
  let timelineSections: number[] = [];
  let timelineStart = DEFAULT_DAY_START;
  let timelineEnd = DEFAULT_DAY_START + MIN_TIMELINE_SPAN;
  let timelineRowCount = MIN_TIMELINE_SPAN / TIME_STEP_MINUTES;
  let timelineGuides: number[] = [];
  let timelineHours: number[] = [];

  function blankDraft(): CourseMutation {
    return {
      id: null,
      name: '',
      teacher: '',
      room: '',
      credit: null,
      day: ((new Date().getDay() + 6) % 7) + 1,
      startSection: 1,
      endSection: 2,
      start: '',
      end: '',
      weeks: Array.from({ length: preferences.weekCount || 20 }, (_, index) => index + 1)
    };
  }

  function weekContext(date: Date) {
    const sundayFirst = preferences.weekStartsOn === 7;
    const jsDay = date.getDay();
    const offset = sundayFirst ? jsDay : (jsDay + 6) % 7;
    const start = new Date(date);
    start.setHours(12, 0, 0, 0);
    start.setDate(date.getDate() - offset);
    const order = sundayFirst ? [7, 1, 2, 3, 4, 5, 6] : [1, 2, 3, 4, 5, 6, 7];
    const days = new Map<number, { label: string; dayNumber: number; date: string; month: number; fullDate: Date }>();

    order.forEach((dayNumber, index) => {
      const item = new Date(start);
      item.setDate(start.getDate() + index);
      days.set(dayNumber, {
        label: weekdayNames[dayNumber - 1],
        dayNumber,
        date: String(item.getDate()).padStart(2, '0'),
        month: item.getMonth() + 1,
        fullDate: item
      });
    });

    return { days, order };
  }

  function sameDay(left: Date, right: Date) {
    return left.getFullYear() === right.getFullYear() && left.getMonth() === right.getMonth() && left.getDate() === right.getDate();
  }

  function normalizedWeekendMode(): WeekendMode {
    if (preferences.weekendMode) return preferences.weekendMode;
    return preferences.showWeekend === false ? 'weekdays' : 'auto';
  }

  function resolveVisibleDayNumbers(mode: WeekendMode, order: number[]) {
    const included = new Set([1, 2, 3, 4, 5]);
    if (mode === 'sat' || mode === 'both') included.add(6);
    if (mode === 'sun' || mode === 'both') included.add(7);
    if (mode === 'auto') {
      if (courses.some((course) => course.day === 6)) included.add(6);
      if (courses.some((course) => course.day === 7)) included.add(7);
    }
    return order.filter((day) => included.has(day));
  }

  function parseClock(raw: string | null | undefined) {
    const match = String(raw || '').trim().match(/^(\d{1,2}):(\d{2})/);
    if (!match) return null;
    const hour = Number(match[1]);
    const minute = Number(match[2]);
    if (!Number.isInteger(hour) || !Number.isInteger(minute) || hour < 0 || hour > 23 || minute < 0 || minute > 59) return null;
    return hour * 60 + minute;
  }

  function formatClock(minutes: number) {
    const safe = Math.max(0, Math.min(24 * 60 - 1, Math.round(minutes)));
    return `${String(Math.floor(safe / 60)).padStart(2, '0')}:${String(safe % 60).padStart(2, '0')}`;
  }

  function mostCommon(values: number[]) {
    if (!values.length) return null;
    const counts = new Map<number, number>();
    for (const value of values) counts.set(value, (counts.get(value) || 0) + 1);
    let best = values[0];
    let bestCount = 0;
    for (const [value, count] of counts) {
      if (count > bestCount || (count === bestCount && value < best)) {
        best = value;
        bestCount = count;
      }
    }
    return best;
  }

  function inferredSectionStart(section: number) {
    const samples = timeReferenceCourses
      .filter((course) => course.startSection === section)
      .map((course) => parseClock(course.start))
      .filter((value): value is number => value !== null);
    return mostCommon(samples) ?? DEFAULT_DAY_START + (section - 1) * DEFAULT_SECTION_STRIDE;
  }

  function inferredSectionEnd(section: number) {
    const samples = timeReferenceCourses
      .filter((course) => course.endSection === section)
      .map((course) => parseClock(course.end))
      .filter((value): value is number => value !== null);
    return mostCommon(samples) ?? inferredSectionStart(section) + DEFAULT_SECTION_LENGTH;
  }

  function courseStartMinute(course: Course) {
    return parseClock(course.start) ?? inferredSectionStart(Math.max(1, course.startSection || 1));
  }

  function courseEndMinute(course: Course) {
    const start = courseStartMinute(course);
    const inferred = parseClock(course.end) ?? inferredSectionEnd(Math.max(course.startSection || 1, course.endSection || course.startSection || 1));
    return Math.max(start + TIME_STEP_MINUTES, inferred);
  }

  function paddedTimelineRange(earliest: number, latest: number, hasCourses: boolean) {
    if (!hasCourses) return { start: DEFAULT_DAY_START, end: DEFAULT_DAY_START + MIN_TIMELINE_SPAN };

    const earliestAllowed = earliest >= DEFAULT_DAY_START
      ? DEFAULT_DAY_START
      : Math.max(0, Math.floor((earliest - TIMELINE_PADDING_MINUTES) / 30) * 30);
    let start = Math.max(earliestAllowed, Math.floor((earliest - TIMELINE_PADDING_MINUTES) / 30) * 30);
    let end = Math.min(24 * 60, Math.ceil((latest + TIMELINE_PADDING_MINUTES) / 30) * 30);

    if (end - start < MIN_TIMELINE_SPAN) {
      const center = (start + end) / 2;
      start = Math.max(earliestAllowed, Math.floor((center - MIN_TIMELINE_SPAN / 2) / 30) * 30);
      end = Math.min(24 * 60, start + MIN_TIMELINE_SPAN);
      if (end - start < MIN_TIMELINE_SPAN) start = Math.max(earliestAllowed, end - MIN_TIMELINE_SPAN);
    }

    return { start, end };
  }

  function timelineRow(minutes: number) {
    return Math.max(1, Math.floor((minutes - timelineStart) / TIME_STEP_MINUTES) + 1);
  }

  function courseGridPlacement(course: Course) {
    const startMinute = Math.max(timelineStart, courseStartMinute(course));
    const endMinute = Math.min(timelineEnd, Math.max(startMinute + TIME_STEP_MINUTES, courseEndMinute(course)));
    const startRow = timelineRow(startMinute);
    const endRow = Math.max(startRow + 1, Math.ceil((endMinute - timelineStart) / TIME_STEP_MINUTES) + 1);
    return `grid-column:${columnFor(course.day) + 1};grid-row:${startRow} / ${endRow}`;
  }

  function sectionMarkerRow(section: number) {
    return timelineRow(inferredSectionStart(section));
  }

  function columnFor(day: number) {
    const index = visibleDayNumbers.indexOf(day);
    return index >= 0 ? index + 1 : 1;
  }

  function weeksToText(weeks: number[]) {
    if (!weeks.length) return `1-${preferences.weekCount || 20}`;
    const sorted = [...new Set(weeks)].sort((a, b) => a - b);
    const parts: string[] = [];
    let start = sorted[0];
    let previous = sorted[0];

    for (let index = 1; index <= sorted.length; index += 1) {
      const value = sorted[index];
      if (value === previous + 1) {
        previous = value;
        continue;
      }
      parts.push(start === previous ? `${start}` : `${start}-${previous}`);
      start = value;
      previous = value;
    }

    return parts.join(',');
  }

  function parseWeeks(raw: string) {
    const result = new Set<number>();
    const tokens = raw.replace(/，/g, ',').split(/[\s,]+/).map((token) => token.trim()).filter(Boolean);

    for (const token of tokens) {
      const range = token.match(/^(\d{1,2})\s*[-~至]\s*(\d{1,2})$/);
      if (range) {
        const from = Math.max(1, Math.min(64, Number(range[1])));
        const to = Math.max(1, Math.min(64, Number(range[2])));
        for (let week = Math.min(from, to); week <= Math.max(from, to); week += 1) result.add(week);
        continue;
      }
      const week = Number(token);
      if (Number.isInteger(week) && week >= 1 && week <= 64) result.add(week);
    }

    return [...result].sort((a, b) => a - b);
  }

  function creditLabel(value: number | null | undefined) {
    if (value == null || !Number.isFinite(value)) return '';
    const text = Number.isInteger(value) ? String(value) : String(Number(value.toFixed(2)));
    return `${text} 学分`;
  }

  function newCourse() {
    draft = blankDraft();
    weeksText = `1-${preferences.weekCount || 20}`;
    creditText = '';
    editorError = '';
    editorOpen = true;
  }

  function editCourse(course: Course) {
    draft = {
      id: course.id,
      name: course.name,
      teacher: course.teacher,
      room: course.room,
      credit: course.credit ?? null,
      day: course.day,
      startSection: course.startSection,
      endSection: course.endSection,
      start: course.start,
      end: course.end,
      weeks: [...course.weeks]
    };
    weeksText = weeksToText(course.weeks);
    creditText = course.credit == null ? '' : String(course.credit);
    editorError = '';
    editorOpen = true;
  }

  async function saveEditor() {
    editorError = '';
    if (!draft.name.trim()) {
      editorError = '请输入课程名称。';
      return;
    }
    if (draft.endSection < draft.startSection) {
      editorError = '结束节次不能早于开始节次。';
      return;
    }

    const weeks = parseWeeks(weeksText);
    if (!weeks.length) {
      editorError = '请输入有效周次，例如 1-16 或 1,3,5,7。';
      return;
    }

    const credit = creditText.trim() ? Number(creditText.trim()) : null;
    if (credit !== null && (!Number.isFinite(credit) || credit < 0 || credit > 30)) {
      editorError = '学分请输入 0–30 之间的数字，例如 3 或 2.5。';
      return;
    }

    editorBusy = true;
    try {
      await saveScheduleCourse({
        ...draft,
        name: draft.name.trim(),
        teacher: draft.teacher.trim(),
        room: draft.room.trim(),
        credit,
        weeks
      });
      editorOpen = false;
      dispatch('changed');
    } catch (error) {
      editorError = error instanceof Error ? error.message : String(error);
    } finally {
      editorBusy = false;
    }
  }

  function requestDelete() {
    if (draft.id && !editorBusy) deleteConfirmOpen = true;
  }

  async function confirmDelete() {
    if (!draft.id || editorBusy) return;
    editorBusy = true;
    editorError = '';
    try {
      await deleteScheduleCourse(draft.id, draft.name);
      deleteConfirmOpen = false;
      editorOpen = false;
      dispatch('changed');
    } catch (error) {
      editorError = error instanceof Error ? error.message : String(error);
    } finally {
      editorBusy = false;
    }
  }

  onMount(() => {
    timer = setInterval(() => (now = new Date()), 60_000);
  });

  onDestroy(() => {
    if (timer) clearInterval(timer);
  });

  $: if (addRequest !== handledAddRequest) {
    handledAddRequest = addRequest;
    if (addRequest > 0) newCourse();
  }

  $: context = weekContext(displayDate ?? now);
  $: weekendMode = normalizedWeekendMode();
  $: visibleDayNumbers = resolveVisibleDayNumbers(weekendMode, context.order);
  $: visibleDays = visibleDayNumbers.map((day) => context.days.get(day)).filter((day): day is NonNullable<typeof day> => Boolean(day));
  $: visibleCourses = courses.filter((course) => visibleDayNumbers.includes(course.day));
  $: dayCount = visibleDayNumbers.length;
  $: timeReferenceCourses = timelineCourses.length ? timelineCourses : courses;
  $: sectionCount = visibleCourses.length
    ? Math.max(1, ...visibleCourses.map((course) => course.endSection || course.startSection || 1))
    : Math.max(1, preferences.defaultSections || 12);
  $: sections = Array.from({ length: sectionCount }, (_, i) => i + 1);
  $: visibleStartMinutes = visibleCourses.map((course) => courseStartMinute(course));
  $: visibleEndMinutes = visibleCourses.map((course) => courseEndMinute(course));
  $: earliestVisible = visibleStartMinutes.length ? Math.min(...visibleStartMinutes) : DEFAULT_DAY_START;
  $: latestVisible = visibleEndMinutes.length ? Math.max(...visibleEndMinutes) : DEFAULT_DAY_START + DEFAULT_SECTION_LENGTH;
  $: timelineRange = paddedTimelineRange(earliestVisible, latestVisible, visibleCourses.length > 0);
  $: timelineStart = timelineRange.start;
  $: timelineEnd = timelineRange.end;
  $: timelineSections = sections.filter((section) => {
    const minute = inferredSectionStart(section);
    return minute >= timelineStart && minute < timelineEnd;
  });
  $: timelineRowCount = Math.max(1, Math.ceil((timelineEnd - timelineStart) / TIME_STEP_MINUTES));
  $: timelineGuides = Array.from(
    { length: Math.floor((timelineEnd - Math.ceil(timelineStart / 30) * 30) / 30) + 1 },
    (_, index) => Math.ceil(timelineStart / 30) * 30 + index * 30
  ).filter((minute) => minute >= timelineStart && minute <= timelineEnd);
  $: timelineHours = timelineGuides.filter((minute) => minute % 60 === 0);
  $: firstVisibleDay = visibleDays[0];
  $: lastVisibleDay = visibleDays[visibleDays.length - 1];
  $: rangeLabel = firstVisibleDay && lastVisibleDay
    ? `${firstVisibleDay.month}月${firstVisibleDay.fullDate.getDate()}日 – ${lastVisibleDay.month}月${lastVisibleDay.fullDate.getDate()}日`
    : '';
  $: title = currentWeek ? `第 ${currentWeek} 周` : '周课表';
  $: subtitle = [
    termName,
    visibleCourses.length ? `${visibleCourses.length} 个课程时段` : hasSchedule ? '本周暂无课程' : '还没有课表'
  ].filter(Boolean).join(' · ');
</script>

<section class="page-week-core">
  {#if showTopbar}
    <header class="topbar week-topbar">
      <div>
        <span class="eyebrow">{rangeLabel}</span>
        <h1>{title}</h1>
        <p>{subtitle}</p>
      </div>
      <button class="week-add glass-panel" on:click={newCourse} aria-label="新增课程">
        <Plus size={22} strokeWidth={1.9} />
      </button>
    </header>
  {/if}

  <div
    class="week-board time-proportional"
    class:compact={preferences.compactMode}
    style={`--day-count:${dayCount};--axis-width:46px;--time-row-count:${timelineRowCount};--time-step-height:${preferences.compactMode ? 4.4 : 5}px`}
  >
    <div class="week-header">
      <div class="corner">{preferences.showTime ? '时间' : '节次'}</div>
      {#each visibleDays as day}
        <div class:today={sameDay(day.fullDate, now)}>
          <span>{day.label}</span>
          <b>{day.date}</b>
        </div>
      {/each}
    </div>

    <div class="week-scroll">
      <div class="week-time-grid">
        {#each timelineGuides as minute}
          <div
            class="time-guide"
            class:major={minute % 60 === 0}
            style={`grid-column:1 / -1;grid-row:${timelineRow(minute)}`}
          ></div>
        {/each}

        {#if preferences.showTime}
          {#each timelineHours as minute}
            <div class="time-label" style={`grid-column:1;grid-row:${timelineRow(minute)}`}>
              {formatClock(minute)}
            </div>
          {/each}
        {:else}
          {#each timelineSections as section}
            <div class="time-label section-label" style={`grid-column:1;grid-row:${sectionMarkerRow(section)}`}>
              {section}
            </div>
          {/each}
        {/if}

        {#each visibleCourses as course}
          <button
            class="week-course {course.color}"
            style={courseGridPlacement(course)}
            aria-label={`编辑 ${course.name}`}
            on:click={() => editCourse(course)}
          >
            <b>{course.name}</b>
            {#if preferences.showTeacher && course.teacher}<span>{course.teacher}</span>{/if}
            {#if preferences.showRoom && course.room}<span>{course.room}</span>{/if}
            {#if creditLabel(course.credit)}<small class="course-credit">{creditLabel(course.credit)}</small>{/if}
          </button>
        {/each}

        {#if !visibleCourses.length}
          <div class="week-empty">
            {hasSchedule ? `第 ${currentWeek || ''} 周没有课程。` : '还没有课表。你可以导入教务课表，也可以在顶部点 + 手动添加。'}
          </div>
        {/if}
      </div>
    </div>
  </div>
</section>

{#if editorOpen}
  <div
    class="course-editor-backdrop"
    role="presentation"
    on:click={(event) => {
      if (event.currentTarget === event.target && !editorBusy) editorOpen = false;
    }}
  >
    <div class="course-editor glass-panel refract" role="dialog" aria-modal="true" aria-label={draft.id ? '编辑课程' : '新增课程'}>
      <header class="editor-head">
        <div>
          <span>{draft.id ? '编辑课程时段' : '手动添加'}</span>
          <h2>{draft.id ? draft.name || '课程' : '新增课程'}</h2>
        </div>
        <button on:click={() => (editorOpen = false)} disabled={editorBusy} aria-label="关闭">
          <X size={19} />
        </button>
      </header>

      <div class="editor-form">
        <label class="wide"><span>课程名称</span><input bind:value={draft.name} placeholder="例如 高等数学" /></label>
        <label><span>老师</span><input bind:value={draft.teacher} placeholder="可选" /></label>
        <label><span>教室</span><input bind:value={draft.room} placeholder="可选" /></label>
        <label><span>学分</span><input bind:value={creditText} inputmode="decimal" placeholder="例如 3.0" /></label>
        <label>
          <span>星期</span>
          <select bind:value={draft.day}>
            {#each weekdayNames as label, index}<option value={index + 1}>{label}</option>{/each}
          </select>
        </label>
        <label>
          <span>开始节次</span>
          <select bind:value={draft.startSection}>
            {#each sectionOptions as section}<option value={section}>第 {section} 节</option>{/each}
          </select>
        </label>
        <label>
          <span>结束节次</span>
          <select bind:value={draft.endSection}>
            {#each sectionOptions as section}<option value={section}>第 {section} 节</option>{/each}
          </select>
        </label>
        <label><span>开始时间</span><input type="time" bind:value={draft.start} /></label>
        <label><span>结束时间</span><input type="time" bind:value={draft.end} /></label>
        <label class="wide">
          <span>上课周次</span>
          <input bind:value={weeksText} placeholder="1-16,18,20" />
          <small>开始/结束时间会直接决定课程块在时间轴上的位置和高度；没有时间时才按节次推算。</small>
        </label>
      </div>

      {#if editorError}<div class="editor-error">{editorError}</div>{/if}

      <footer class="editor-actions">
        {#if draft.id}
          <button class="delete-course" on:click={requestDelete} disabled={editorBusy}>
            <Trash2 size={16} /> 删除此时段
          </button>
        {/if}
        <button class="save-course" on:click={saveEditor} disabled={editorBusy}>
          {editorBusy ? '正在保存…' : '保存'}
        </button>
      </footer>
    </div>
  </div>
{/if}

<ConfirmSheet
  open={deleteConfirmOpen}
  title="删除课程时段"
  message="只删除当前这个上课时段。若同一门课还有其他时段，它们会继续保留。"
  confirmText="删除"
  danger
  busy={editorBusy}
  on:cancel={() => (deleteConfirmOpen = false)}
  on:confirm={confirmDelete}
/>

<style>
  .page-week-core {
    position: relative;
    width: 100%;
    min-width: 0;
    min-height: 0;
    display: flex;
    flex-direction: column;
    overflow: visible;
  }

  .week-topbar { align-items: center; }

  .week-add {
    width: 44px;
    height: 44px;
    border-radius: 22px;
    border: 0;
    display: grid;
    place-items: center;
    color: #5b56d6;
    flex: none;
  }

  .week-board {
    width: 100%;
    min-width: 0;
    display: flex;
    flex-direction: column;
    overflow: visible;
    border: 0;
    border-radius: 14px;
    background: rgba(255, 255, 255, .24);
    box-shadow: none;
  }

  .week-board .week-header {
    flex: 0 0 auto;
    display: grid;
    grid-template-columns: var(--axis-width) repeat(var(--day-count), minmax(0, 1fr));
    height: 52px;
    background: rgba(255, 255, 255, .90);
    box-shadow: inset 0 -1px rgba(60, 60, 67, .05);
  }

  .week-board .week-header > div {
    min-width: 0;
    display: grid;
    place-content: center;
    justify-items: center;
    gap: 2px;
    color: rgba(42, 42, 48, .72);
  }

  .week-board .week-header > div span {
    font-size: 10.5px;
    line-height: 1.05;
    font-weight: 620;
    white-space: nowrap;
  }

  .week-board .week-header > div b {
    font-size: 13.5px;
    line-height: 1;
    font-weight: 760;
  }

  .week-board .week-header .corner {
    font-size: 9.5px;
    font-weight: 680;
    color: rgba(42, 42, 48, .58);
  }

  .week-board .week-header .today {
    color: #514bc2;
    background: rgba(91, 86, 214, .10);
  }

  .week-scroll {
    position: relative;
    overflow: visible;
    background: transparent;
  }

  .week-time-grid {
    position: relative;
    display: grid;
    grid-template-columns: var(--axis-width) repeat(var(--day-count), minmax(0, 1fr));
    grid-template-rows: repeat(var(--time-row-count), var(--time-step-height));
    width: 100%;
    min-width: 0;
    min-height: calc(var(--time-row-count) * var(--time-step-height));
    background: rgba(255, 255, 255, .08);
  }

  .time-guide {
    z-index: 0;
    height: 0;
    align-self: start;
    border-top: 1px solid rgba(77, 82, 102, .028);
    pointer-events: none;
  }

  .time-guide.major {
    border-top-color: rgba(77, 82, 102, .075);
  }

  .time-label {
    z-index: 2;
    align-self: start;
    justify-self: stretch;
    transform: translateY(-50%);
    padding-right: 5px;
    text-align: right;
    color: rgba(41, 41, 48, .68);
    font-size: 8.8px;
    line-height: 1;
    font-weight: 650;
    font-variant-numeric: tabular-nums;
    pointer-events: none;
  }

  .section-label {
    font-size: 9.5px;
  }

  .week-course {
    position: relative;
    min-width: 0;
    min-height: 0;
    z-index: 3;
    margin: 2px 1.5px;
    padding: 5px 4px;
    border: 1px solid rgba(62, 60, 78, .05);
    border-radius: 8px;
    display: flex;
    flex-direction: column;
    justify-content: flex-start;
    gap: 1px;
    text-align: left;
    font: inherit;
    cursor: pointer;
    overflow: hidden;
    color: #24232b;
    background: linear-gradient(145deg, #cbc6ff, #aaa2f8);
    box-shadow: none;
  }

  .week-course.cyan { background: linear-gradient(145deg, #adebf0, #7fd5df); }
  .week-course.amber { background: linear-gradient(145deg, #ffe5a9, #f3c66f); }
  .week-course.blue { background: linear-gradient(145deg, #c4e1ff, #8fbeef); }
  .week-course.green { background: linear-gradient(145deg, #c2eccf, #8bd0a5); }
  .week-course.pink { background: linear-gradient(145deg, #f5c7e1, #e79bc3); }
  .week-course.orange { background: linear-gradient(145deg, #f7c7aa, #e99a6d); }

  .week-course b {
    display: block;
    overflow: visible;
    white-space: normal;
    word-break: break-word;
    overflow-wrap: anywhere;
    font-size: 11.2px;
    line-height: 1.16;
    font-weight: 760;
  }

  .week-course span {
    display: block;
    overflow: visible;
    white-space: normal;
    word-break: break-word;
    overflow-wrap: anywhere;
    font-size: 9px;
    line-height: 1.12;
    font-weight: 540;
    color: rgba(36, 35, 43, .78);
  }

  .week-course .course-credit {
    display: block;
    font-size: 8.7px;
    line-height: 1.08;
    font-weight: 720;
    color: rgba(36, 35, 43, .74);
  }

  .week-empty {
    position: absolute;
    left: var(--axis-width);
    right: 0;
    top: 150px;
    z-index: 4;
    margin: auto;
    max-width: 250px;
    padding: 14px;
    text-align: center;
    color: rgba(60, 60, 67, .48);
    font-size: 12px;
    line-height: 1.55;
    pointer-events: none;
  }

  .course-editor-backdrop {
    position: fixed;
    inset: 0;
    z-index: 120;
    display: flex;
    align-items: flex-end;
    justify-content: center;
    overflow: hidden;
    background: rgba(18, 18, 24, .22);
    backdrop-filter: blur(4px);
    -webkit-backdrop-filter: blur(4px);
  }

  .course-editor {
    position: relative;
    box-sizing: border-box;
    width: min(620px, 100vw);
    max-width: 100vw;
    max-height: min(88dvh, 760px);
    overflow-y: auto;
    overflow-x: hidden;
    overscroll-behavior: contain;
    touch-action: pan-y;
    border-radius: 30px 30px 0 0;
    padding: 14px 18px calc(20px + env(safe-area-inset-bottom));
  }

  .editor-head {
    display: flex;
    align-items: center;
    gap: 14px;
    margin-bottom: 16px;
  }

  .editor-head > div { flex: 1; min-width: 0; }
  .editor-head span { font-size: 11px; color: rgba(60, 60, 67, .56); }
  .editor-head h2 {
    margin: 2px 0 0;
    font-size: 23px;
    line-height: 1.12;
    letter-spacing: -.035em;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .editor-head button {
    width: 38px;
    height: 38px;
    border: 0;
    border-radius: 19px;
    background: rgba(118, 118, 128, .10);
    display: grid;
    place-items: center;
    color: rgba(60, 60, 67, .62);
  }

  .editor-form {
    box-sizing: border-box;
    width: 100%;
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
    gap: 10px;
  }

  .editor-form label {
    box-sizing: border-box;
    display: flex;
    flex-direction: column;
    gap: 6px;
    min-width: 0;
  }

  .editor-form label.wide { grid-column: 1 / -1; }
  .editor-form label > span {
    font-size: 11px;
    color: rgba(60, 60, 67, .62);
    padding-left: 4px;
  }

  .editor-form input,
  .editor-form select {
    box-sizing: border-box;
    width: 100%;
    min-width: 0;
    min-height: 46px;
    border: 1px solid rgba(60, 60, 67, .08);
    border-radius: 14px;
    background: rgba(118, 118, 128, .09);
    color: #111114;
    padding: 0 13px;
    outline: none;
  }

  .editor-form input:focus,
  .editor-form select:focus {
    border-color: rgba(91, 86, 214, .35);
    background: rgba(255, 255, 255, .46);
  }

  .editor-form small {
    font-size: 10px;
    line-height: 1.45;
    color: rgba(60, 60, 67, .48);
    padding-left: 4px;
  }

  .editor-error {
    margin-top: 12px;
    border-radius: 14px;
    padding: 11px 13px;
    font-size: 12px;
    color: #b34f5b;
    background: rgba(220, 70, 84, .08);
  }

  .editor-actions {
    display: flex;
    align-items: center;
    gap: 10px;
    margin-top: 16px;
  }

  .editor-actions button {
    min-height: 46px;
    border: 0;
    border-radius: 15px;
    font-weight: 650;
  }

  .delete-course {
    padding: 0 14px;
    display: inline-flex;
    align-items: center;
    gap: 7px;
    color: #c44d5e;
    background: rgba(220, 70, 84, .09);
  }

  .save-course {
    margin-left: auto;
    min-width: 118px;
    padding: 0 22px;
    color: white;
    background: #5b56d6;
    box-shadow: 0 9px 20px rgba(91, 86, 214, .22);
  }

  .editor-actions button:disabled { opacity: .55; }

  @media (min-width: 761px) {
    .course-editor-backdrop { align-items: center; padding: 24px; }
    .course-editor { border-radius: 30px; }
  }
</style>