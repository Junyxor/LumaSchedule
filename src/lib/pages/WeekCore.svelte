<script lang="ts">
  import { Plus, Trash2, X } from 'lucide-svelte';
  import { createEventDispatcher, onDestroy, onMount } from 'svelte';
  import ConfirmSheet from '../components/ConfirmSheet.svelte';
  import { deleteScheduleCourse, saveScheduleCourse } from '../tauri';
  import type { Course, CourseMutation, SchedulePreferences, WeekendMode } from '../types';

  export let courses: Course[];
  export let hasSchedule = false;
  export let currentWeek: number | null | undefined = null;
  export let termName: string | null | undefined = null;
  export let displayDate: Date | null = null;
  export let showTopbar = true;
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

  function courseMeta(course: Course) {
    return [preferences.showRoom ? course.room : '', preferences.showTeacher ? course.teacher : ''].filter(Boolean).join(' · ');
  }

  function columnFor(day: number) {
    const index = visibleDayNumbers.indexOf(day);
    return index >= 0 ? index + 1 : 1;
  }

  function sectionStart(section: number) {
    return courses.find((course) => course.startSection === section && course.start.trim())?.start.trim() ?? '';
  }

  function sectionEnd(section: number) {
    return courses.find((course) => course.endSection === section && course.end.trim())?.end.trim() ?? '';
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
      await deleteScheduleCourse(draft.id);
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

  $: context = weekContext(displayDate ?? now);
  $: weekendMode = normalizedWeekendMode();
  $: visibleDayNumbers = resolveVisibleDayNumbers(weekendMode, context.order);
  $: visibleDays = visibleDayNumbers.map((day) => context.days.get(day)).filter((day): day is NonNullable<typeof day> => Boolean(day));
  $: visibleCourses = courses.filter((course) => visibleDayNumbers.includes(course.day));
  $: dayCount = visibleDayNumbers.length;
  $: sectionCount = Math.max(preferences.defaultSections || 12, ...visibleCourses.map((course) => course.endSection || 0));
  $: sections = Array.from({ length: sectionCount }, (_, i) => i + 1);
  $: rowHeight = preferences.compactMode ? 58 : 70;
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
    class="week-board content-surface"
    class:compact={preferences.compactMode}
    style={`--section-count:${sectionCount};--day-count:${dayCount};--row-height:${rowHeight}px;--axis-width:62px`}
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
      <div class="week-body-grid">
        {#each sections as section}
          <div class="section-time" style={`grid-column:1;grid-row:${section}`}>
            <small>第 {section} 节</small>
            {#if preferences.showTime && sectionStart(section)}<b>{sectionStart(section)}</b>{/if}
            {#if preferences.showTime && sectionEnd(section)}<span>{sectionEnd(section)}</span>{/if}
          </div>
        {/each}

        {#each visibleDayNumbers as _, dayIndex}
          {#each sections as section}
            <div class="week-cell" style={`grid-column:${dayIndex + 2};grid-row:${section}`}></div>
          {/each}
        {/each}

        {#each visibleCourses as course}
          <button
            class="week-course {course.color}"
            style={`grid-column:${columnFor(course.day) + 1};grid-row:${course.startSection} / span ${course.endSection - course.startSection + 1}`}
            aria-label={`编辑 ${course.name}`}
            on:click={() => editCourse(course)}
          >
            <b>{course.name}</b>
            {#if courseMeta(course)}<span>{courseMeta(course)}</span>{/if}
          </button>
        {/each}

        {#if !visibleCourses.length}
          <div class="week-empty">
            {hasSchedule ? `第 ${currentWeek || ''} 周没有课程。` : '还没有课表。你可以导入教务课表，也可以点右上角 + 手动添加。'}
          </div>
        {/if}
      </div>
    </div>
  </div>

  {#if !showTopbar}
    <button class="floating-week-add glass-panel" on:click={newCourse} aria-label="新增课程">
      <Plus size={21} strokeWidth={1.9} />
    </button>
  {/if}
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
          <small>支持 1-16、1,3,5,7、1-8,10-16。学分会用于同名成绩缺少学分时自动匹配。</small>
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
  }

  .week-topbar { align-items: center; }

  .week-add,
  .floating-week-add {
    border: 0;
    display: grid;
    place-items: center;
    color: #5b56d6;
  }

  .week-add {
    width: 46px;
    height: 46px;
    border-radius: 23px;
    flex: none;
  }

  .floating-week-add {
    position: absolute;
    right: 10px;
    top: 14px;
    z-index: 8;
    width: 42px;
    height: 42px;
    border-radius: 21px;
  }

  .week-board .week-header {
    display: grid;
    grid-template-columns: var(--axis-width) repeat(var(--day-count), minmax(0, 1fr));
    height: 62px;
  }

  .week-board .week-header > div {
    min-width: 0;
  }

  .week-board .week-header .corner {
    font-size: 9px;
    letter-spacing: .02em;
  }

  .week-board .week-scroll {
    position: relative;
    padding-left: 0;
    overflow-x: hidden;
    overflow-y: auto;
    height: calc(100dvh - 186px);
    min-height: 520px;
  }

  .week-body-grid {
    position: relative;
    display: grid;
    grid-template-columns: var(--axis-width) repeat(var(--day-count), minmax(0, 1fr));
    grid-template-rows: repeat(var(--section-count), var(--row-height));
    width: 100%;
    min-width: 0;
    min-height: calc(var(--section-count) * var(--row-height));
    background: rgba(255, 255, 255, .22);
  }

  .section-time {
    z-index: 1;
    min-width: 0;
    display: grid;
    align-content: center;
    justify-items: center;
    gap: 2px;
    padding: 4px 2px;
    border-right: 1px solid rgba(77, 82, 102, .08);
    border-bottom: 1px solid rgba(77, 82, 102, .07);
    background: rgba(255, 255, 255, .34);
    color: rgba(60, 60, 67, .58);
    font-variant-numeric: tabular-nums;
  }

  .section-time small {
    font-size: 7.5px;
    line-height: 1.05;
    color: rgba(60, 60, 67, .42);
  }

  .section-time b {
    font-size: 9.5px;
    line-height: 1.1;
    font-weight: 650;
    color: rgba(45, 45, 52, .72);
  }

  .section-time span {
    font-size: 8px;
    line-height: 1.05;
    color: rgba(60, 60, 67, .48);
  }

  .week-cell {
    z-index: 0;
    min-width: 0;
    border-left: 1px solid rgba(77, 82, 102, .055);
    border-bottom: 1px solid rgba(77, 82, 102, .07);
  }

  .week-body-grid .week-course {
    position: relative;
    left: auto;
    top: auto;
    width: auto;
    height: auto;
    min-width: 0;
    min-height: 0;
    align-self: stretch;
    justify-self: stretch;
    z-index: 3;
    margin: 4px 3px;
    padding: 8px 6px;
    border: 0;
    border-radius: 12px;
    text-align: left;
    font: inherit;
    cursor: pointer;
    overflow: hidden;
  }

  .week-body-grid .week-course b {
    display: -webkit-box;
    -webkit-line-clamp: 3;
    -webkit-box-orient: vertical;
    overflow: hidden;
    word-break: break-all;
  }

  .week-body-grid .week-course span {
    display: block;
    margin-top: 4px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .week-board.compact .week-course {
    padding: 7px 5px;
    border-radius: 10px;
  }

  .week-board.compact .week-course b {
    font-size: 9px;
  }

  .week-empty {
    position: sticky;
    left: var(--axis-width);
    grid-column: 2 / -1;
    grid-row: 2 / span 2;
    z-index: 4;
    align-self: center;
    justify-self: center;
    max-width: 260px;
    padding: 18px;
    text-align: center;
    color: rgba(60, 60, 67, .55);
    font-size: 13px;
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

  .editor-head > div {
    flex: 1;
    min-width: 0;
  }

  .editor-head span {
    font-size: 11px;
    color: rgba(60, 60, 67, .56);
  }

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

  @media (max-width: 760px) {
    .week-board {
      --axis-width: 58px !important;
    }

    .week-board .week-header {
      height: 58px;
    }

    .week-board .week-scroll {
      height: calc(100dvh - 176px);
      min-height: 540px;
    }

    .week-body-grid .week-course {
      margin: 3px 2px;
      padding: 7px 5px;
      border-radius: 11px;
    }

    .section-time small { font-size: 7px; }
    .section-time b { font-size: 9px; }
    .section-time span { font-size: 7.5px; }
  }

  @media (min-width: 761px) {
    .course-editor-backdrop {
      align-items: center;
      padding: 24px;
    }

    .course-editor { border-radius: 30px; }
  }
</style>
