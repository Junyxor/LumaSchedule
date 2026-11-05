<script lang="ts">
  import { CalendarCheck } from 'lucide-svelte';
  import { untrack } from 'svelte';
  import { api } from '../native/api';
  import { errorMessage } from '../native/bridge';
  import { monthDayLabel, parseIsoDate, startOfWeek, teachingWeek } from '../schedule/calendar';
  import { app } from '../state/app.svelte';
  import { toast } from '../state/toast.svelte';
  import Segmented from '../ui/Segmented.svelte';

  const initial = untrack(() => app.semester);
  let termName = $state(initial.termName);
  let termStart = $state(initial.termStart);
  let weekCount = $state(initial.weekCount || 20);
  let weekStartsOn = $state(initial.weekStartsOn === 7 ? 7 : 1);
  let timezone = $state(initial.timezone || 'Asia/Shanghai');
  let busy = $state(false);

  const preview = $derived.by(() => {
    const start = parseIsoDate(termStart);
    if (!start) return '未设置开学日期时，课程按每周显示，也无法安排上课提醒。';
    const week = teachingWeek(app.now, start, weekStartsOn);
    const firstDay = startOfWeek(start, weekStartsOn);
    if (week < 1) return `第 1 周从 ${monthDayLabel(firstDay)} 开始，还没开学。`;
    if (week > weekCount) return `按当前设置，学期已在第 ${weekCount} 周结束。`;
    return `第 1 周从 ${monthDayLabel(firstDay)} 开始，今天是第 ${week} 周。`;
  });

  async function save() {
    const count = Math.trunc(Number(weekCount));
    if (!Number.isFinite(count) || count < 1 || count > 64) {
      toast.error('总周数需要在 1 到 64 之间。');
      return;
    }
    if (termStart && !parseIsoDate(termStart)) {
      toast.error('开学日期格式不正确。');
      return;
    }
    busy = true;
    try {
      await api.saveTermSettings({ termName: termName.trim(), termStart, weekCount: count, weekStartsOn, timezone: timezone.trim() || 'Asia/Shanghai' });
      toast.success('学期设置已保存');
      await app.reload();
    } catch (error) {
      toast.error(errorMessage(error));
    } finally {
      busy = false;
    }
  }
</script>

{#if !app.semester.hasSchedule}
  <div class="notice"><CalendarCheck size={18} /><span>还没有课表。保存后会创建一份空白课表，之后可以导入或手动添加课程。</span></div>
{/if}

<div class="card stack">
  <label class="field">
    <span class="label">学期名称</span>
    <input class="input" bind:value={termName} placeholder="例如 2026–2027 学年第一学期" maxlength="60" />
  </label>
  <div class="form-grid">
    <label class="field">
      <span class="label">开学日期</span>
      <input class="input" type="date" bind:value={termStart} />
    </label>
    <label class="field">
      <span class="label">总周数</span>
      <input class="input" type="number" min="1" max="64" inputmode="numeric" bind:value={weekCount} />
    </label>
  </div>
  <div class="field">
    <span class="label">每周第一天</span>
    <Segmented
      options={[
        { value: 1, label: '周一' },
        { value: 7, label: '周日' }
      ]}
      value={weekStartsOn}
      onchange={(value) => (weekStartsOn = value)}
      label="每周第一天"
    />
  </div>
  <label class="field">
    <span class="label">时区</span>
    <input class="input" bind:value={timezone} placeholder="Asia/Shanghai" autocomplete="off" />
    <span class="hint">提醒、小组件和日历导出都按这个时区计算。</span>
  </label>
  <p class="preview">{preview}</p>
  <button class="btn btn-primary btn-lg btn-block" type="button" onclick={save} disabled={busy}>
    {#if busy}<span class="spinner"></span>{/if} 保存
  </button>
</div>

<style>
  .notice {
    margin-bottom: 12px;
  }

  .preview {
    padding: 10px 12px;
    border-radius: var(--radius-md);
    background: var(--accent-softer);
    color: var(--text-2);
    font-size: var(--text-sm);
    line-height: 1.55;
  }
</style>
