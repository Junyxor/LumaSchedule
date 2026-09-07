<script lang="ts">
  import { confirm } from '@tauri-apps/plugin-dialog';
  import { Plus, Trash2, X } from 'lucide-svelte';
  import { createEventDispatcher, onDestroy, onMount } from 'svelte';
  import { deleteScheduleCourse, saveScheduleCourse } from '../tauri';
  import type { Course, CourseMutation } from '../types';

  export let courses: Course[];
  export let hasSchedule = false;
  export let currentWeek: number | null | undefined = null;
  export let termName: string | null | undefined = null;

  const dispatch = createEventDispatcher<{ changed: void }>();
  const weekdayNames = ['周一','周二','周三','周四','周五','周六','周日'];
  const sectionOptions = Array.from({ length: 20 }, (_, index) => index + 1);
  let now = new Date();
  let timer: ReturnType<typeof setInterval> | null = null;
  let editorOpen = false;
  let editorBusy = false;
  let editorError = '';
  let weeksText = '1-20';
  let draft: CourseMutation = blankDraft();

  function blankDraft(): CourseMutation {
    return {
      id: null,
      name: '',
      teacher: '',
      room: '',
      day: ((new Date().getDay() + 6) % 7) + 1,
      startSection: 1,
      endSection: 2,
      start: '',
      end: '',
      weeks: Array.from({ length: 20 }, (_, index) => index + 1)
    };
  }

  function weekContext(date: Date) {
    const monday = new Date(date);
    monday.setHours(0, 0, 0, 0);
    monday.setDate(date.getDate() - ((date.getDay() + 6) % 7));
    const days = weekdayNames.map((label, index) => {
      const item = new Date(monday);
      item.setDate(monday.getDate() + index);
      return { label, date: String(item.getDate()).padStart(2, '0') };
    });
    const sunday = new Date(monday);
    sunday.setDate(monday.getDate() + 6);
    return {
      days,
      todayIndex: ((date.getDay() + 6) % 7),
      rangeLabel: `${monday.getMonth() + 1}月${monday.getDate()}日 – ${sunday.getMonth() + 1}月${sunday.getDate()}日`
    };
  }

  function weeksToText(weeks: number[]) {
    if (!weeks.length) return '1-20';
    const sorted = [...new Set(weeks)].sort((a, b) => a - b);
    const parts: string[] = [];
    let start = sorted[0];
    let previous = sorted[0];
    for (let index = 1; index <= sorted.length; index += 1) {
      const value = sorted[index];
      if (value === previous + 1) { previous = value; continue; }
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

  function newCourse() {
    draft = blankDraft();
    weeksText = '1-20';
    editorError = '';
    editorOpen = true;
  }

  function editCourse(course: Course) {
    draft = {
      id: course.id,
      name: course.name,
      teacher: course.teacher,
      room: course.room,
      day: course.day,
      startSection: course.startSection,
      endSection: course.endSection,
      start: course.start,
      end: course.end,
      weeks: [...course.weeks]
    };
    weeksText = weeksToText(course.weeks);
    editorError = '';
    editorOpen = true;
  }

  async function saveEditor() {
    editorError = '';
    if (!draft.name.trim()) { editorError = '请输入课程名称。'; return; }
    if (draft.endSection < draft.startSection) { editorError = '结束节次不能早于开始节次。'; return; }
    const weeks = parseWeeks(weeksText);
    if (!weeks.length) { editorError = '请输入有效周次，例如 1-16 或 1,3,5,7。'; return; }
    editorBusy = true;
    try {
      await saveScheduleCourse({ ...draft, name: draft.name.trim(), teacher: draft.teacher.trim(), room: draft.room.trim(), weeks });
      editorOpen = false;
      dispatch('changed');
    } catch (error) {
      editorError = error instanceof Error ? error.message : String(error);
    } finally {
      editorBusy = false;
    }
  }

  async function removeEditorCourse() {
    if (!draft.id || editorBusy) return;
    const approved = await confirm('只删除当前这个上课时段。若同一门课还有其他时段，它们会继续保留。', { title: '删除课程时段', kind: 'warning' });
    if (!approved) return;
    editorBusy = true;
    editorError = '';
    try {
      await deleteScheduleCourse(draft.id);
      editorOpen = false;
      dispatch('changed');
    } catch (error) {
      editorError = error instanceof Error ? error.message : String(error);
    } finally {
      editorBusy = false;
    }
  }

  onMount(() => { timer = setInterval(() => (now = new Date()), 60_000); });
  onDestroy(() => { if (timer) clearInterval(timer); });

  $: context = weekContext(now);
  $: sectionCount = Math.max(12, ...courses.map((course) => course.endSection || 0));
  $: sections = Array.from({ length: sectionCount }, (_, i) => i + 1);
  $: title = currentWeek ? `第 ${currentWeek} 周` : '本周课表';
  $: subtitle = [termName, courses.length ? `${courses.length} 个课程时段` : hasSchedule ? '当前周暂无课程' : '还没有课表'].filter(Boolean).join(' · ');
</script>

<section class="page page-week">
  <header class="topbar week-topbar">
    <div>
      <span class="eyebrow">{context.rangeLabel}</span>
      <h1>{title}</h1>
      <p>{subtitle}</p>
    </div>
    <button class="week-add glass-panel" on:click={newCourse} aria-label="新增课程"><Plus size={22} strokeWidth={1.9} /></button>
  </header>

  <div class="week-board content-surface" style={`--section-count:${sectionCount}`}>
    <div class="week-header">
      <div class="corner">节</div>
      {#each context.days as day, i}<div class:today={i === context.todayIndex}><span>{day.label}</span><b>{day.date}</b></div>{/each}
    </div>
    <div class="week-scroll">
      <div class="section-column">{#each sections as n}<div><b>{n}</b></div>{/each}</div>
      <div class="week-gridlines">{#each Array(7) as _}<div></div>{/each}</div>
      {#each courses as course}
        <button class="week-course {course.color}" style={`--day:${course.day};--start:${course.startSection};--span:${course.endSection - course.startSection + 1}`} aria-label={`编辑 ${course.name}`} on:click={() => editCourse(course)}>
          <b>{course.name}</b>
          <span>{course.room || ''}</span>
          {#if course.start}<small>{course.start}</small>{/if}
        </button>
      {/each}
      {#if !courses.length}<div class="week-empty">{hasSchedule ? '当前周没有课程。点右上角 + 可以手动添加。' : '还没有课表。你可以导入教务课表，也可以点右上角 + 手动添加。'}</div>{/if}
    </div>
  </div>
</section>

{#if editorOpen}
  <div class="course-editor-backdrop" role="presentation" on:click={(event) => { if (event.currentTarget === event.target && !editorBusy) editorOpen = false; }}>
    <div class="course-editor glass-panel refract" role="dialog" aria-modal="true" aria-label={draft.id ? '编辑课程' : '新增课程'}>
      <div class="editor-grabber" aria-hidden="true"></div>
      <header class="editor-head">
        <div><span>{draft.id ? '编辑课程时段' : '手动添加'}</span><h2>{draft.id ? draft.name || '课程' : '新增课程'}</h2></div>
        <button on:click={() => (editorOpen = false)} disabled={editorBusy} aria-label="关闭"><X size={19} /></button>
      </header>

      <div class="editor-form">
        <label class="wide"><span>课程名称</span><input bind:value={draft.name} placeholder="例如 高等数学" /></label>
        <label><span>老师</span><input bind:value={draft.teacher} placeholder="可选" /></label>
        <label><span>教室</span><input bind:value={draft.room} placeholder="可选" /></label>
        <label><span>星期</span><select bind:value={draft.day}>{#each weekdayNames as label, index}<option value={index + 1}>{label}</option>{/each}</select></label>
        <label><span>开始节次</span><select bind:value={draft.startSection}>{#each sectionOptions as section}<option value={section}>第 {section} 节</option>{/each}</select></label>
        <label><span>结束节次</span><select bind:value={draft.endSection}>{#each sectionOptions as section}<option value={section}>第 {section} 节</option>{/each}</select></label>
        <label><span>开始时间</span><input type="time" bind:value={draft.start} /></label>
        <label><span>结束时间</span><input type="time" bind:value={draft.end} /></label>
        <label class="wide"><span>上课周次</span><input bind:value={weeksText} placeholder="1-16,18,20" /><small>支持 1-16、1,3,5,7、1-8,10-16</small></label>
      </div>

      {#if editorError}<div class="editor-error">{editorError}</div>{/if}
      <footer class="editor-actions">
        {#if draft.id}<button class="delete-course" on:click={removeEditorCourse} disabled={editorBusy}><Trash2 size={16} /> 删除此时段</button>{/if}
        <button class="save-course" on:click={saveEditor} disabled={editorBusy}>{editorBusy ? '正在保存…' : '保存'}</button>
      </footer>
    </div>
  </div>
{/if}

<style>
  .week-topbar { align-items: center; }
  .week-add {
    width: 46px;
    height: 46px;
    border-radius: 23px;
    border: 0;
    display: grid;
    place-items: center;
    color: #5b56d6;
    flex: none;
  }
  .week-course { border: 0; text-align: left; font: inherit; cursor: pointer; }
  .course-editor-backdrop {
    position: fixed;
    inset: 0;
    z-index: 120;
    display: flex;
    align-items: flex-end;
    justify-content: center;
    background: rgba(18,18,24,.22);
    backdrop-filter: blur(4px);
    -webkit-backdrop-filter: blur(4px);
  }
  .course-editor {
    position: relative;
    width: min(620px, 100%);
    max-height: min(88dvh, 760px);
    overflow: auto;
    border-radius: 30px 30px 0 0;
    padding: 8px 18px calc(20px + env(safe-area-inset-bottom));
  }
  .editor-grabber { width: 38px; height: 5px; border-radius: 999px; background: rgba(60,60,67,.22); margin: 0 auto 10px; }
  .editor-head { display: flex; align-items: center; gap: 14px; margin-bottom: 16px; }
  .editor-head > div { flex: 1; min-width: 0; }
  .editor-head span { font-size: 11px; color: rgba(60,60,67,.56); }
  .editor-head h2 { margin: 2px 0 0; font-size: 23px; line-height: 1.12; letter-spacing: -.035em; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .editor-head button { width: 38px; height: 38px; border: 0; border-radius: 19px; background: rgba(118,118,128,.10); display: grid; place-items: center; color: rgba(60,60,67,.62); }
  .editor-form { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
  .editor-form label { display: flex; flex-direction: column; gap: 6px; min-width: 0; }
  .editor-form label.wide { grid-column: 1 / -1; }
  .editor-form label > span { font-size: 11px; color: rgba(60,60,67,.62); padding-left: 4px; }
  .editor-form input, .editor-form select {
    width: 100%;
    min-height: 46px;
    border: 1px solid rgba(60,60,67,.08);
    border-radius: 14px;
    background: rgba(118,118,128,.09);
    color: #111114;
    padding: 0 13px;
    outline: none;
  }
  .editor-form input:focus, .editor-form select:focus { border-color: rgba(91,86,214,.35); background: rgba(255,255,255,.46); }
  .editor-form small { font-size: 10px; color: rgba(60,60,67,.48); padding-left: 4px; }
  .editor-error { margin-top: 12px; border-radius: 14px; padding: 11px 13px; font-size: 12px; color: #b34f5b; background: rgba(220,70,84,.08); }
  .editor-actions { display: flex; align-items: center; gap: 10px; margin-top: 16px; }
  .editor-actions button { min-height: 46px; border: 0; border-radius: 15px; font-weight: 650; }
  .delete-course { padding: 0 14px; display: inline-flex; align-items: center; gap: 7px; color: #c44d5e; background: rgba(220,70,84,.09); }
  .save-course { margin-left: auto; min-width: 118px; padding: 0 22px; color: white; background: #5b56d6; box-shadow: 0 9px 20px rgba(91,86,214,.22); }
  .editor-actions button:disabled { opacity: .55; }

  @media (min-width: 761px) {
    .course-editor-backdrop { align-items: center; padding: 24px; }
    .course-editor { border-radius: 30px; }
  }
</style>
