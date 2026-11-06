<script lang="ts">
  import { Check } from 'lucide-svelte';
  import { COURSE_COLORS } from '../schedule/courseSheets.svelte';
  import { app } from '../state/app.svelte';
  import type { AccentId, ThemeMode } from '../types';
  import Row from '../ui/Row.svelte';
  import Segmented from '../ui/Segmented.svelte';

  const accents: { id: AccentId; label: string; color: string }[] = [
    { id: 'luma', label: '流光紫', color: '#5b56d6' },
    { id: 'blue', label: '晴空蓝', color: '#2563eb' },
    { id: 'teal', label: '青碧', color: '#0f8b80' },
    { id: 'green', label: '松绿', color: '#2f8f3e' },
    { id: 'orange', label: '暖橙', color: '#d4610f' },
    { id: 'pink', label: '樱粉', color: '#cc2f74' },
    { id: 'graphite', label: '石墨', color: '#3f4656' }
  ];

  const sample = [
    { name: '高等数学', room: '教 6-204', color: 'violet' },
    { name: '大学英语', room: '教 3-106', color: 'amber' },
    { name: '程序设计', room: '实验 4-302', color: 'cyan' }
  ];

  function setTheme(theme: ThemeMode) {
    app.setAppearance({ ...app.appearance, theme });
  }
</script>

<div class="list-caption">主题</div>
<Segmented
  options={[
    { value: 'system', label: '跟随系统' },
    { value: 'light', label: '浅色' },
    { value: 'dark', label: '深色' }
  ]}
  value={app.appearance.theme}
  onchange={setTheme}
  label="主题"
/>

<div class="list-caption">主题色</div>
<div class="card accents">
  {#each accents as accent (accent.id)}
    <button class="accent" type="button" aria-pressed={app.appearance.accent === accent.id} onclick={() => app.setAppearance({ ...app.appearance, accent: accent.id })}>
      <span class="dot" style:background={accent.color}>{#if app.appearance.accent === accent.id}<Check size={16} />{/if}</span>
      <span class="name">{accent.label}</span>
    </button>
  {/each}
</div>

<div class="list-caption">课程卡片</div>
<div class="list">
  <Row toggle title="多彩课程" sub="按课程自动分配颜色；关闭后统一使用主题色" checked={app.appearance.colorfulCourses} onclick={() => app.setAppearance({ ...app.appearance, colorfulCourses: !app.appearance.colorfulCourses })} />
</div>
<div class="preview">
  {#each sample as item (item.name)}
    <div class="sample {app.appearance.colorfulCourses ? `tone-${item.color}` : 'tone-neutral'}">
      <b>{item.name}</b>
      <span>{item.room}</span>
    </div>
  {/each}
</div>
<p class="list-footer">单门课程的颜色可以在课程编辑页单独指定，可选 {COURSE_COLORS.length} 种颜色。</p>

<style>
  .accents {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 12px 6px;
  }

  .accent {
    display: grid;
    justify-items: center;
    gap: 6px;
    padding: 4px 0;
    border: 0;
    background: none;
  }

  .dot {
    display: grid;
    place-items: center;
    width: 38px;
    height: 38px;
    border-radius: 50%;
    color: #fff;
  }

  .accent[aria-pressed='true'] .dot {
    box-shadow: 0 0 0 3px var(--surface), 0 0 0 5px var(--accent);
  }

  .name {
    color: var(--text-2);
    font-size: var(--text-xs);
  }

  .preview {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 6px;
    margin-top: 10px;
  }

  .sample {
    display: grid;
    gap: 2px;
    min-height: 74px;
    padding: 8px 8px 8px 10px;
    border-radius: var(--radius-xs);
    background: var(--tone-bg);
    color: var(--tone-fg);
    box-shadow: inset 2.5px 0 0 var(--tone-bar);
    align-content: start;
  }

  .sample b {
    font-size: 13px;
  }

  .sample span {
    font-size: 11px;
    opacity: 0.8;
  }
</style>
