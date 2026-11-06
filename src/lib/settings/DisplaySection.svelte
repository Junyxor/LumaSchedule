<script lang="ts">
  import { Check } from 'lucide-svelte';
  import { errorMessage } from '../native/bridge';
  import { app } from '../state/app.svelte';
  import { toast } from '../state/toast.svelte';
  import type { DisplayPreferences, WeekendMode } from '../types';
  import Row from '../ui/Row.svelte';

  const weekendModes: { value: WeekendMode; title: string; sub: string }[] = [
    { value: 'auto', title: '自动', sub: '有周末课程时才显示周六、周日' },
    { value: 'weekdays', title: '只显示工作日', sub: '周一到周五' },
    { value: 'sat', title: '显示周六', sub: '周一到周六' },
    { value: 'sun', title: '显示周日', sub: '周一到周五和周日' },
    { value: 'both', title: '完整一周', sub: '始终显示七天' }
  ];

  async function update(patch: Partial<DisplayPreferences>) {
    try {
      await app.setDisplay({ ...app.display, ...patch });
    } catch (error) {
      toast.error(errorMessage(error));
    }
  }
</script>

<div class="list-caption">周末</div>
<div class="list">
  {#each weekendModes as mode (mode.value)}
    <button class="list-row" type="button" role="radio" aria-checked={app.display.weekendMode === mode.value} onclick={() => update({ weekendMode: mode.value })}>
      <span class="row-body"><span class="row-title">{mode.title}</span><span class="row-sub">{mode.sub}</span></span>
      {#if app.display.weekendMode === mode.value}<span class="check"><Check size={18} /></span>{/if}
    </button>
  {/each}
</div>

<div class="list-caption">课程卡片</div>
<div class="list">
  <Row toggle title="显示时间" sub="周课表左侧显示节次时间，课程卡显示起止时间" checked={app.display.showTime} onclick={() => update({ showTime: !app.display.showTime })} />
  <Row toggle title="显示教室" checked={app.display.showRoom} onclick={() => update({ showRoom: !app.display.showRoom })} />
  <Row toggle title="显示教师" checked={app.display.showTeacher} onclick={() => update({ showTeacher: !app.display.showTeacher })} />
  <Row toggle title="紧凑模式" sub="缩短周课表的高度，卡片只显示课程名和时间" checked={app.display.compactMode} onclick={() => update({ compactMode: !app.display.compactMode })} />
</div>

<div class="list-caption">节次</div>
<div class="list">
  <div class="list-row">
    <span class="row-body"><span class="row-title">每天显示节数</span><span class="row-sub">超过这个数字的课程仍会自动显示</span></span>
    <select class="select compact" value={app.display.defaultSections} onchange={(event) => update({ defaultSections: Number(event.currentTarget.value) })}>
      {#each Array.from({ length: 23 }, (_, index) => index + 8) as count (count)}
        <option value={count}>{count} 节</option>
      {/each}
    </select>
  </div>
</div>

<style>
  .check {
    color: var(--accent);
  }

  .select.compact {
    width: 96px;
    min-height: 36px;
    flex: none;
  }
</style>
