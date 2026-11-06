<script lang="ts">
  import { Check, TriangleAlert } from 'lucide-svelte';
  import { untrack } from 'svelte';
  import { api } from '../native/api';
  import { errorMessage } from '../native/bridge';
  import { WEEKDAY_SHORT, weeksSummary } from '../schedule/calendar';
  import { app } from '../state/app.svelte';
  import { confirmAction } from '../state/confirm.svelte';
  import { toast } from '../state/toast.svelte';
  import type { ImportBundle, ImportDiff, ImportMode } from '../types';
  import Segmented from '../ui/Segmented.svelte';
  import Sheet from '../ui/Sheet.svelte';
  import { previewTime } from './parsers';

  interface Props {
    bundle: ImportBundle | null;
    label: string;
    onclose: () => void;
  }

  let { bundle, label, onclose }: Props = $props();

  let selected = $state<boolean[]>([]);
  let diff = $state.raw<ImportDiff | null>(null);
  let diffError = $state('');
  let diffLoading = $state(false);
  let mode = $state<ImportMode>('new');
  let committing = $state(false);
  let revision = 0;
  let debounce: ReturnType<typeof setTimeout> | null = null;

  const courses = $derived(bundle?.courses ?? []);
  const selectedCount = $derived(selected.filter(Boolean).length);
  const courseCount = $derived(new Set(courses.map((course) => `${course.name}\u001f${course.teacher ?? ''}`)).size);
  const order = $derived(
    courses
      .map((course, index) => ({ course, index }))
      .sort((a, b) => a.course.weekday - b.course.weekday || a.course.startSection - b.course.startSection || a.course.name.localeCompare(b.course.name, 'zh-CN'))
  );

  $effect(() => {
    const next = bundle;
    untrack(() => {
      selected = next ? next.courses.map(() => true) : [];
      diff = null;
      diffError = '';
      mode = app.semester.hasSchedule ? 'merge' : 'new';
      if (next) scheduleDiff(true);
    });
  });

  function selectedBundle(): ImportBundle | null {
    if (!bundle) return null;
    const picked = bundle.courses.filter((_, index) => selected[index]);
    return picked.length ? { ...bundle, courses: picked } : null;
  }

  function scheduleDiff(immediate = false) {
    if (debounce) clearTimeout(debounce);
    debounce = setTimeout(() => void refreshDiff(), immediate ? 0 : 250);
  }

  async function refreshDiff() {
    const current = selectedBundle();
    const token = ++revision;
    if (!current) {
      diff = null;
      diffLoading = false;
      return;
    }
    diffLoading = true;
    diffError = '';
    try {
      const result = await api.previewImport(current);
      if (token !== revision) return;
      diff = result;
      if (!result.hasExistingSchedule) mode = 'new';
    } catch (error) {
      if (token === revision) diffError = errorMessage(error);
    } finally {
      if (token === revision) diffLoading = false;
    }
  }

  function toggle(index: number) {
    selected[index] = !selected[index];
    scheduleDiff();
  }

  function toggleAll() {
    const value = selectedCount !== courses.length;
    selected = courses.map(() => value);
    scheduleDiff();
  }

  async function commit() {
    const current = selectedBundle();
    if (!current || committing) return;
    if (mode === 'overwrite') {
      const ok = await confirmAction({
        title: '覆盖当前课表？',
        message: `当前课表的 ${diff?.existingMeetingCount ?? 0} 个上课时段会被替换为这次导入的 ${current.courses.length} 个时段。建议先在设置里导出备份。`,
        confirmText: '覆盖',
        danger: true
      });
      if (!ok) return;
    }
    committing = true;
    try {
      const result = await api.commitImport(current, mode);
      const message =
        result.mode === 'merge'
          ? `已合并：新增 ${result.addedCount} 个时段，跳过 ${result.skippedCount} 个重复`
          : result.mode === 'overwrite'
            ? `已覆盖：写入 ${result.meetingCount} 个时段`
            : `已导入 ${result.courseCount} 门课程，共 ${result.meetingCount} 个时段`;
      toast.success(message);
      onclose();
      await app.reload();
      app.go('week');
    } catch (error) {
      toast.error(errorMessage(error));
    } finally {
      committing = false;
    }
  }

  const modeOptions: { value: ImportMode; label: string }[] = [
    { value: 'merge', label: '合并' },
    { value: 'overwrite', label: '覆盖' },
    { value: 'new', label: '新建课表' }
  ];

  const modeHint = $derived(
    mode === 'merge'
      ? '把新课程加入当前课表，完全相同的时段会自动跳过。'
      : mode === 'overwrite'
        ? '清空当前课表的课程后写入本次导入的课程，学期设置会使用导入文件中的值。'
        : '创建一份新课表并切换过去，原课表保留在「设置 → 我的课表」中。'
  );
</script>

<Sheet open={!!bundle} title="导入预览" subtitle={label} size="full" dismissible={!committing} {onclose}>
  {#if bundle}
    <div class="summary">
      <div>
        <b class="num">{courses.length}</b>
        <span>个上课时段</span>
      </div>
      <div>
        <b class="num">{courseCount}</b>
        <span>门课程</span>
      </div>
      <div>
        <b class="num">{bundle.termStart ? bundle.termStart.slice(5).replace('-', '/') : '—'}</b>
        <span>开学日期</span>
      </div>
    </div>
    {#if bundle.termName}<p class="term">{bundle.termName}</p>{/if}

    {#if app.semester.hasSchedule}
      <div class="block">
        <div class="block-title">导入方式</div>
        <Segmented options={modeOptions} value={mode} onchange={(value) => (mode = value)} label="导入方式" />
        <p class="hint">{modeHint}</p>
        {#if diffLoading}
          <div class="diff-loading"><span class="spinner"></span> 正在对比当前课表…</div>
        {:else if diff}
          <div class="diff">
            <div><b class="num">{diff.newCount}</b><span>新增</span></div>
            <div><b class="num">{diff.duplicateCount}</b><span>重复</span></div>
            <div class:warn={diff.conflictCount > 0}><b class="num">{diff.conflictCount}</b><span>时间冲突</span></div>
            <div class:warn={mode === 'overwrite' && diff.removeCount > 0}><b class="num">{mode === 'overwrite' ? diff.existingMeetingCount : 0}</b><span>将被移除</span></div>
          </div>
          {#if mode === 'merge' && diff.conflictCount > 0}
            <div class="notice warning"><TriangleAlert size={16} /><span>有 {diff.conflictCount} 个时段与现有课程时间重叠，合并后两门课都会保留。</span></div>
          {/if}
        {:else if diffError}
          <div class="notice danger"><TriangleAlert size={16} /><span>无法对比当前课表：{diffError}</span></div>
        {/if}
      </div>
    {/if}

    <div class="select-bar">
      <span>已选 <b class="num">{selectedCount}</b> / {courses.length}</span>
      <button class="btn btn-sm btn-ghost" type="button" onclick={toggleAll}>{selectedCount === courses.length ? '全部取消' : '全选'}</button>
    </div>
    <div class="list">
      {#each order as { course, index } (index)}
        <button class="list-row pick" class:off={!selected[index]} type="button" aria-pressed={selected[index]} onclick={() => toggle(index)}>
          <span class="check" class:on={selected[index]}>{#if selected[index]}<Check size={14} strokeWidth={3} />{/if}</span>
          <span class="day">{WEEKDAY_SHORT[course.weekday - 1]}</span>
          <span class="row-body">
            <span class="row-title">{course.name}</span>
            <span class="row-sub num">{[previewTime(course), course.location, course.teacher].filter(Boolean).join(' · ')}</span>
            <span class="row-sub faint">{weeksSummary(course.weeks)}{course.credit != null ? ` · ${course.credit} 学分` : ''}</span>
          </span>
        </button>
      {/each}
    </div>
  {/if}

  {#snippet footer()}
    <button class="btn btn-lg" type="button" onclick={onclose} disabled={committing}>取消</button>
    <button class="btn btn-lg {mode === 'overwrite' ? 'btn-danger' : 'btn-primary'}" style="flex:1" type="button" onclick={commit} disabled={committing || !selectedCount || diffLoading}>
      {#if committing}<span class="spinner"></span>{/if}
      {mode === 'merge' ? `合并 ${selectedCount} 个时段` : mode === 'overwrite' ? `覆盖为 ${selectedCount} 个时段` : `导入 ${selectedCount} 个时段`}
    </button>
  {/snippet}
</Sheet>

<style>
  .summary {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 8px;
  }

  .summary > div,
  .diff > div {
    display: grid;
    gap: 2px;
    padding: 12px;
    border-radius: var(--radius-md);
    background: var(--surface-2);
    border: 1px solid var(--border);
  }

  .summary b {
    font-size: var(--text-xl);
    font-weight: 700;
    line-height: 1.2;
  }

  .summary span,
  .diff span {
    color: var(--text-2);
    font-size: var(--text-xs);
  }

  .term {
    margin-top: 8px;
    color: var(--text-2);
    font-size: var(--text-sm);
  }

  .block {
    display: grid;
    gap: 10px;
    margin-top: 18px;
  }

  .block-title {
    font-size: var(--text-md);
    font-weight: 650;
  }

  .hint {
    color: var(--text-2);
    font-size: var(--text-sm);
    line-height: 1.55;
  }

  .diff {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 6px;
  }

  .diff b {
    font-size: var(--text-lg);
    font-weight: 700;
  }

  .diff .warn {
    border-color: transparent;
    background: var(--warning-soft);
  }

  .diff .warn b {
    color: var(--warning);
  }

  .diff-loading {
    display: flex;
    align-items: center;
    gap: 8px;
    color: var(--text-2);
    font-size: var(--text-sm);
  }

  .select-bar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin: 18px 4px 6px;
    color: var(--text-2);
    font-size: var(--text-sm);
  }

  .select-bar b {
    color: var(--text);
  }

  .pick {
    --row-divider-inset: 84px;
    align-items: flex-start;
  }

  .pick.off .row-body {
    opacity: 0.5;
  }

  .check {
    display: grid;
    place-items: center;
    width: 22px;
    height: 22px;
    flex: none;
    margin-top: 1px;
    border: 1.5px solid var(--border-strong);
    border-radius: 7px;
    color: var(--on-accent);
  }

  .check.on {
    border-color: var(--accent);
    background: var(--accent);
  }

  .day {
    width: 32px;
    flex: none;
    margin-top: 1px;
    color: var(--text-2);
    font-size: var(--text-sm);
    font-weight: 600;
  }
</style>
