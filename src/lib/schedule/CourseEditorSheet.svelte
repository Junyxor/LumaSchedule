<script lang="ts">
  import { Check, Trash2 } from 'lucide-svelte';
  import { untrack } from 'svelte';
  import { api } from '../native/api';
  import { errorMessage } from '../native/bridge';
  import { app } from '../state/app.svelte';
  import { confirmAction } from '../state/confirm.svelte';
  import { toast } from '../state/toast.svelte';
  import type { CourseColor } from '../types';
  import Segmented from '../ui/Segmented.svelte';
  import Sheet from '../ui/Sheet.svelte';
  import { dayOrder, formatClock, normalizeClock, parseClock, parseWeeksText, WEEKDAY_NARROW, weekdayOf, weeksToText } from './calendar';
  import { COURSE_COLORS, courseSheets } from './courseSheets.svelte';

  const editor = $derived(courseSheets.editor);
  const editing = $derived(editor?.course ?? null);

  let name = $state('');
  let teacher = $state('');
  let room = $state('');
  let day = $state(1);
  let startSection = $state(1);
  let endSection = $state(2);
  let startTime = $state('');
  let endTime = $state('');
  let weeks = $state<number[]>([]);
  let weeksText = $state('');
  let creditText = $state('');
  let color = $state<CourseColor | 'auto'>('auto');
  let error = $state('');
  let busy = $state(false);

  const weekCount = $derived(Math.max(app.ctx.weekCount, ...weeks, 1));
  const sectionOptions = $derived(Array.from({ length: Math.max(app.sectionCount, 12) }, (_, index) => index + 1));
  const dayOptions = $derived(dayOrder(app.ctx.weekStartsOn).map((value) => ({ value, label: WEEKDAY_NARROW[value - 1] })));
  const sectionTimeHint = $derived.by(() => {
    const first = app.sections[startSection - 1];
    const last = app.sections[Math.max(startSection, endSection) - 1];
    return first && last ? `${formatClock(first.start)}–${formatClock(last.end)}` : '';
  });

  $effect(() => {
    const state = editor;
    if (!state) return;
    untrack(() => {
      const course = state.course;
      const preset = state.preset;
      name = course?.name ?? preset.name ?? '';
      teacher = course?.teacher ?? preset.teacher ?? '';
      room = course?.room ?? preset.room ?? '';
      day = course?.day ?? preset.day ?? weekdayOf(app.now);
      startSection = course?.startSection ?? preset.startSection ?? 1;
      endSection = course?.endSection ?? preset.endSection ?? Math.min(startSection + 1, sectionOptions.length);
      startTime = course?.startTime ?? preset.startTime ?? '';
      endTime = course?.endTime ?? preset.endTime ?? '';
      weeks = course ? [...course.weeks] : preset.weeks ?? Array.from({ length: app.ctx.weekCount }, (_, index) => index + 1);
      if (!weeks.length) weeks = Array.from({ length: app.ctx.weekCount }, (_, index) => index + 1);
      weeksText = weeksToText(weeks);
      creditText = course?.credit != null ? String(course.credit) : '';
      color = course ? (course.customColor ? course.color : 'auto') : 'auto';
      error = '';
      busy = false;
    });
  });

  function setWeeks(next: number[]) {
    weeks = [...new Set(next)].filter((week) => week >= 1 && week <= 64).sort((a, b) => a - b);
    weeksText = weeksToText(weeks);
  }

  function toggleWeek(week: number) {
    setWeeks(weeks.includes(week) ? weeks.filter((value) => value !== week) : [...weeks, week]);
  }

  function preset(kind: 'all' | 'odd' | 'even' | 'none') {
    const all = Array.from({ length: app.ctx.weekCount }, (_, index) => index + 1);
    setWeeks(kind === 'all' ? all : kind === 'odd' ? all.filter((week) => week % 2 === 1) : kind === 'even' ? all.filter((week) => week % 2 === 0) : []);
  }

  function applyWeeksText() {
    const parsed = parseWeeksText(weeksText, app.ctx.weekCount);
    if (parsed.length) setWeeks(parsed);
    else weeksText = weeksToText(weeks);
  }

  function changeStart(value: number) {
    startSection = value;
    if (endSection < value) endSection = value;
  }

  async function save() {
    error = '';
    const trimmed = name.trim();
    if (!trimmed) {
      error = '请填写课程名称。';
      return;
    }
    if (endSection < startSection) {
      error = '结束节次不能早于开始节次。';
      return;
    }
    const start = normalizeClock(startTime);
    const end = normalizeClock(endTime);
    if ((start && !end) || (!start && end)) {
      error = '开始和结束时间需要同时填写，或者都留空按作息时间。';
      return;
    }
    if (start && end && (parseClock(end) ?? 0) <= (parseClock(start) ?? 0)) {
      error = '结束时间需要晚于开始时间。';
      return;
    }
    if (!weeks.length) {
      error = '至少选择一个上课周。';
      return;
    }
    const credit = creditText.trim() ? Number(creditText.trim()) : null;
    if (credit !== null && (!Number.isFinite(credit) || credit < 0 || credit > 30)) {
      error = '学分请输入 0–30 之间的数字。';
      return;
    }
    busy = true;
    try {
      await api.saveCourse({
        id: editing?.id ?? null,
        name: trimmed,
        teacher: teacher.trim(),
        room: room.trim(),
        day,
        startSection,
        endSection,
        startTime: start,
        endTime: end,
        weeks,
        credit,
        color
      });
      courseSheets.closeEditor();
      toast.success(editing ? '已保存修改' : '已添加课程');
      await app.reload();
    } catch (cause) {
      error = errorMessage(cause);
    } finally {
      busy = false;
    }
  }

  async function remove() {
    if (!editing || busy) return;
    const ok = await confirmAction({ title: '删除这个上课时段？', message: '这门课的其他时段会保留。', confirmText: '删除', danger: true });
    if (!ok) return;
    busy = true;
    try {
      await api.deleteCourse(editing.id);
      courseSheets.closeEditor();
      toast.success('已删除');
      await app.reload();
    } catch (cause) {
      error = errorMessage(cause);
    } finally {
      busy = false;
    }
  }
</script>

<Sheet open={!!editor} title={editing ? '编辑课程' : '添加课程'} size="tall" dismissible={!busy} onclose={() => courseSheets.closeEditor()}>
  <form
    class="form"
    onsubmit={(event) => {
      event.preventDefault();
      void save();
    }}
  >
    <label class="field">
      <span class="label">课程名称</span>
      <input class="input" bind:value={name} placeholder="例如 高等数学" maxlength="60" autocomplete="off" />
    </label>

    <div class="form-grid">
      <label class="field">
        <span class="label">教室</span>
        <input class="input" bind:value={room} placeholder="可选" maxlength="60" autocomplete="off" />
      </label>
      <label class="field">
        <span class="label">教师</span>
        <input class="input" bind:value={teacher} placeholder="可选" maxlength="60" autocomplete="off" />
      </label>
    </div>

    <div class="field">
      <span class="label">星期</span>
      <Segmented options={dayOptions} value={day} onchange={(value) => (day = value)} label="星期" />
    </div>

    <div class="form-grid">
      <label class="field">
        <span class="label">开始节次</span>
        <select class="select" value={startSection} onchange={(event) => changeStart(Number(event.currentTarget.value))}>
          {#each sectionOptions as section (section)}
            <option value={section}>第 {section} 节{app.sections[section - 1] ? ` · ${formatClock(app.sections[section - 1].start)}` : ''}</option>
          {/each}
        </select>
      </label>
      <label class="field">
        <span class="label">结束节次</span>
        <select class="select" bind:value={endSection}>
          {#each sectionOptions.filter((section) => section >= startSection) as section (section)}
            <option value={section}>第 {section} 节{app.sections[section - 1] ? ` · ${formatClock(app.sections[section - 1].end)}` : ''}</option>
          {/each}
        </select>
      </label>
    </div>

    <div class="form-grid">
      <label class="field">
        <span class="label">开始时间</span>
        <input class="input" type="time" bind:value={startTime} />
      </label>
      <label class="field">
        <span class="label">结束时间</span>
        <input class="input" type="time" bind:value={endTime} />
      </label>
      <span class="hint span-2">留空时按作息时间{sectionTimeHint ? `（${sectionTimeHint}）` : ''}；只有这节课时间特殊时才需要填写。</span>
    </div>

    <div class="field">
      <span class="label">上课周（已选 {weeks.length} 周）</span>
      <div class="week-presets">
        <button type="button" class="chip" onclick={() => preset('all')}>全部</button>
        <button type="button" class="chip" onclick={() => preset('odd')}>单周</button>
        <button type="button" class="chip" onclick={() => preset('even')}>双周</button>
        <button type="button" class="chip" onclick={() => preset('none')}>清空</button>
      </div>
      <div class="weeks">
        {#each Array.from({ length: weekCount }, (_, index) => index + 1) as week (week)}
          <button type="button" class:on={weeks.includes(week)} aria-pressed={weeks.includes(week)} onclick={() => toggleWeek(week)}>{week}</button>
        {/each}
      </div>
      <input
        class="input"
        bind:value={weeksText}
        onblur={applyWeeksText}
        onkeydown={(event) => {
          if (event.key === 'Enter') {
            event.preventDefault();
            applyWeeksText();
          }
        }}
        placeholder="也可以直接输入：1-16 或 1-15单"
        aria-label="周次文本"
      />
    </div>

    <div class="form-grid">
      <label class="field">
        <span class="label">学分</span>
        <input class="input" bind:value={creditText} inputmode="decimal" placeholder="可选，例如 2.5" />
      </label>
    </div>

    <div class="field">
      <span class="label">颜色</span>
      <div class="colors">
        <button type="button" class="swatch auto" class:on={color === 'auto'} aria-label="自动配色" onclick={() => (color = 'auto')}>
          {#if color === 'auto'}<Check size={14} />{:else}A{/if}
        </button>
        {#each COURSE_COLORS as tone (tone)}
          <button type="button" class="swatch tone-{tone}" class:on={color === tone} aria-label={tone} onclick={() => (color = tone)}>
            {#if color === tone}<Check size={14} />{/if}
          </button>
        {/each}
      </div>
      <span class="hint">课程名称、教师、学分和颜色会同步到这门课的所有时段。{app.appearance.colorfulCourses ? '' : '当前使用统一配色，可在 设置 → 外观 开启多彩课程。'}</span>
    </div>

    {#if error}<div class="notice danger">{error}</div>{/if}
    <button type="submit" hidden aria-hidden="true" tabindex="-1"></button>
  </form>

  {#snippet footer()}
    {#if editing}
      <button class="btn btn-lg btn-danger" type="button" onclick={remove} disabled={busy} aria-label="删除"><Trash2 size={17} /></button>
    {/if}
    <button class="btn btn-lg btn-primary" style="flex:1" type="button" onclick={save} disabled={busy}>
      {#if busy}<span class="spinner"></span>{/if}
      {editing ? '保存' : '添加'}
    </button>
  {/snippet}
</Sheet>

<style>
  .form {
    display: grid;
    gap: 16px;
    padding-top: 4px;
  }

  .hint {
    color: var(--text-3);
    font-size: var(--text-xs);
    line-height: 1.5;
  }

  .week-presets {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
  }

  .weeks {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(38px, 1fr));
    gap: 6px;
  }

  .weeks button {
    min-height: 34px;
    border: 1px solid var(--border);
    border-radius: var(--radius-xs);
    background: var(--surface-2);
    color: var(--text-2);
    font-size: var(--text-sm);
    font-variant-numeric: tabular-nums;
  }

  .weeks button.on {
    border-color: var(--accent);
    background: var(--accent);
    color: var(--on-accent);
    font-weight: 600;
  }

  .colors {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
  }

  .swatch {
    display: grid;
    place-items: center;
    width: 34px;
    height: 34px;
    border: 2px solid transparent;
    border-radius: 50%;
    background: var(--tone-bar);
    color: #fff;
    font-size: var(--text-xs);
    font-weight: 700;
  }

  .swatch.auto {
    background: var(--fill);
    color: var(--text-2);
  }

  .swatch.on {
    border-color: var(--surface-raised);
    box-shadow: 0 0 0 2px var(--accent);
  }
</style>
