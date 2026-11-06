<script lang="ts">
  import { RefreshCw } from 'lucide-svelte';
  import { api } from '../native/api';
  import { errorMessage } from '../native/bridge';
  import { durationLabel, findUpcoming, formatClock, minutesOfDay, relativeDayLabel } from '../schedule/calendar';
  import { app } from '../state/app.svelte';
  import { toast } from '../state/toast.svelte';
  import Row from '../ui/Row.svelte';

  const upcoming = $derived(findUpcoming(app.semester, app.ctx, app.sections, app.now));
  const nowMinute = $derived(minutesOfDay(app.now));
  const preview = $derived.by(() => {
    if (!app.semester.hasSchedule) return { label: 'LumaSchedule', name: '还没有课表', meta: '打开应用导入课表', side: '' };
    const current = upcoming.current;
    if (current) return { label: '正在上课', name: current.course.name, meta: `至 ${formatClock(current.end)}${current.course.room ? ` · ${current.course.room}` : ''}`, side: `剩 ${durationLabel(current.end - nowMinute)}` };
    const next = upcoming.next;
    if (!next) return { label: '下一节', name: '近期没有课程', meta: '好好休息', side: '' };
    const day = relativeDayLabel(next.date, app.now);
    return {
      label: day === '今天' ? '下一节' : `${day}`,
      name: next.course.name,
      meta: `${formatClock(next.start)}–${formatClock(next.end)}${next.course.room ? ` · ${next.course.room}` : ''}`,
      side: day === '今天' && next.start - nowMinute <= 120 ? `${durationLabel(next.start - nowMinute)}后` : formatClock(next.start)
    };
  });

  async function refresh() {
    try {
      await api.refreshWidgets();
      toast.success('小组件已刷新');
    } catch (error) {
      toast.error(errorMessage(error));
    }
  }
</script>

<div class="stage">
  <div class="widget">
    <div class="w-main">
      <span class="w-label">{preview.label}</span>
      <b>{preview.name}</b>
      <span class="w-meta">{preview.meta}</span>
    </div>
    {#if preview.side}<span class="w-side num">{preview.side}</span>{/if}
  </div>
</div>
<p class="list-footer center">小组件会在课程开始和结束时自动更新，不需要打开应用。</p>

<div class="list-caption">添加到桌面</div>
<div class="card steps">
  <p>1. 在桌面空白处长按，选择「小组件」或「窗口小工具」。</p>
  <p>2. 找到 LumaSchedule，拖动「下一节课」到桌面。</p>
  <p>3. 点击小组件可以直接打开今日页面。</p>
</div>

<div class="list-caption">维护</div>
<div class="list">
  <Row icon={RefreshCw} title="立即刷新小组件" sub="如果桌面显示的内容不对，可以手动刷新" onclick={refresh} />
</div>

<style>
  .stage {
    display: grid;
    place-items: center;
    padding: 28px 16px;
    border-radius: var(--radius-lg);
    background: linear-gradient(160deg, #8f9bb3, #5d6987);
  }

  .widget {
    display: flex;
    align-items: center;
    gap: 12px;
    width: min(100%, 320px);
    padding: 14px 16px;
    border-radius: 22px;
    background: rgba(255, 255, 255, 0.94);
    color: #1b1d27;
    box-shadow: 0 10px 30px rgba(0, 0, 0, 0.18);
  }

  :global([data-theme='dark']) .widget {
    background: rgba(30, 32, 40, 0.95);
    color: #eceef4;
  }

  .w-main {
    display: grid;
    flex: 1;
    min-width: 0;
  }

  .w-label {
    color: var(--accent);
    font-size: 11px;
    font-weight: 650;
  }

  .w-main b {
    overflow: hidden;
    font-size: 16px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .w-meta {
    overflow: hidden;
    opacity: 0.6;
    font-size: 11.5px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .w-side {
    flex: none;
    color: var(--accent);
    font-size: 13px;
    font-weight: 700;
  }

  .center {
    text-align: center;
  }

  .steps {
    display: grid;
    gap: 6px;
    color: var(--text-2);
    font-size: var(--text-md);
    line-height: 1.6;
  }
</style>
