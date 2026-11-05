<script lang="ts">
  import { Minus, Plus, Wand2 } from 'lucide-svelte';
  import { untrack } from 'svelte';
  import { api } from '../native/api';
  import { errorMessage } from '../native/bridge';
  import { formatClock, parseClock } from '../schedule/calendar';
  import { app } from '../state/app.svelte';
  import { toast } from '../state/toast.svelte';
  import type { TimeSlot } from '../types';
  import Sheet from '../ui/Sheet.svelte';

  const saved = untrack(() => app.semester.timeSlots.length > 0);
  let slots = $state<TimeSlot[]>(untrack(() => app.sections.map((section) => ({ section: section.section, start: formatClock(section.start), end: formatClock(section.end) }))));
  let busy = $state(false);
  let generatorOpen = $state(false);
  let gen = $state({ first: '08:00', length: 45, gap: 10, count: 12, afternoonFrom: 5, afternoonAt: '14:00', eveningFrom: 9, eveningAt: '19:00' });

  const problems = $derived.by(() => {
    const list: string[] = [];
    slots.forEach((slot, index) => {
      const start = parseClock(slot.start);
      const end = parseClock(slot.end);
      if (start === null || end === null) list.push(`第 ${slot.section} 节时间不完整`);
      else if (end <= start) list.push(`第 ${slot.section} 节的结束时间早于开始时间`);
      const previous = slots[index - 1];
      const previousEnd = previous ? parseClock(previous.end) : null;
      if (start !== null && previousEnd !== null && start < previousEnd) list.push(`第 ${slot.section} 节与上一节重叠`);
    });
    return list;
  });

  function addSection() {
    const last = slots[slots.length - 1];
    const lastEnd = last ? (parseClock(last.end) ?? 8 * 60) : 7 * 60 + 50;
    const start = Math.min(23 * 60, lastEnd + 10);
    slots = [...slots, { section: slots.length + 1, start: formatClock(start), end: formatClock(Math.min(24 * 60 - 1, start + 45)) }];
  }

  function removeSection() {
    if (slots.length > 1) slots = slots.slice(0, -1);
  }

  function generate() {
    const result: TimeSlot[] = [];
    let cursor = parseClock(gen.first) ?? 8 * 60;
    const length = Math.max(10, Math.min(180, Math.trunc(gen.length)));
    const gap = Math.max(0, Math.min(120, Math.trunc(gen.gap)));
    const count = Math.max(1, Math.min(30, Math.trunc(gen.count)));
    for (let section = 1; section <= count; section += 1) {
      if (section === gen.afternoonFrom && parseClock(gen.afternoonAt) !== null) cursor = Math.max(cursor, parseClock(gen.afternoonAt)!);
      if (section === gen.eveningFrom && parseClock(gen.eveningAt) !== null) cursor = Math.max(cursor, parseClock(gen.eveningAt)!);
      const end = Math.min(24 * 60 - 1, cursor + length);
      result.push({ section, start: formatClock(cursor), end: formatClock(end) });
      cursor = end + gap;
    }
    slots = result;
    generatorOpen = false;
  }

  async function save() {
    if (problems.length) {
      toast.error(problems[0]);
      return;
    }
    busy = true;
    try {
      await api.saveTimeSlots(slots.map((slot, index) => ({ section: index + 1, start: slot.start, end: slot.end })));
      if (app.display.defaultSections !== slots.length) await app.setDisplay({ ...app.display, defaultSections: Math.max(1, Math.min(30, slots.length)) });
      toast.success('作息时间已保存');
      await app.reload();
    } catch (error) {
      toast.error(errorMessage(error));
    } finally {
      busy = false;
    }
  }
</script>

{#if !app.semester.hasSchedule}
  <div class="notice">还没有课表。保存后会创建一份空白课表并使用这套作息时间。</div>
{:else if !saved}
  <div class="notice">当前作息时间是根据课程推算的，确认或修改后保存即可固定下来。</div>
{/if}

<div class="toolbar">
  <button class="btn btn-sm btn-tonal" type="button" onclick={() => (generatorOpen = true)}><Wand2 size={15} /> 按规则生成</button>
  <span class="spacer"></span>
  <button class="icon-btn filled" type="button" aria-label="删除最后一节" onclick={removeSection} disabled={slots.length <= 1}><Minus size={18} /></button>
  <span class="count num">{slots.length} 节</span>
  <button class="icon-btn filled" type="button" aria-label="增加一节" onclick={addSection} disabled={slots.length >= 30}><Plus size={18} /></button>
</div>

<div class="list">
  {#each slots as slot, index (index)}
    <div class="list-row slot">
      <span class="no num">第 {index + 1} 节</span>
      <input class="input" type="time" bind:value={slot.start} aria-label="第 {index + 1} 节开始时间" />
      <span class="dash">–</span>
      <input class="input" type="time" bind:value={slot.end} aria-label="第 {index + 1} 节结束时间" />
    </div>
  {/each}
</div>

{#if problems.length}<p class="list-footer warn">{problems.join('；')}</p>{/if}
<p class="list-footer">没有单独设置时间的课程会按这里的节次时间显示、提醒和导出。</p>

<button class="btn btn-primary btn-lg btn-block save" type="button" onclick={save} disabled={busy}>
  {#if busy}<span class="spinner"></span>{/if} 保存作息时间
</button>

<Sheet open={generatorOpen} title="按规则生成" subtitle="生成后仍可逐节修改" onclose={() => (generatorOpen = false)}>
  <div class="stack">
    <div class="form-grid">
      <label class="field"><span class="label">第一节开始</span><input class="input" type="time" bind:value={gen.first} /></label>
      <label class="field"><span class="label">节数</span><input class="input" type="number" min="1" max="30" bind:value={gen.count} /></label>
      <label class="field"><span class="label">每节分钟</span><input class="input" type="number" min="10" max="180" bind:value={gen.length} /></label>
      <label class="field"><span class="label">课间分钟</span><input class="input" type="number" min="0" max="120" bind:value={gen.gap} /></label>
      <label class="field"><span class="label">下午从第几节</span><input class="input" type="number" min="1" max="30" bind:value={gen.afternoonFrom} /></label>
      <label class="field"><span class="label">下午开始</span><input class="input" type="time" bind:value={gen.afternoonAt} /></label>
      <label class="field"><span class="label">晚上从第几节</span><input class="input" type="number" min="1" max="30" bind:value={gen.eveningFrom} /></label>
      <label class="field"><span class="label">晚上开始</span><input class="input" type="time" bind:value={gen.eveningAt} /></label>
    </div>
  </div>
  {#snippet footer()}
    <button class="btn btn-lg btn-primary btn-block" type="button" onclick={generate}>生成</button>
  {/snippet}
</Sheet>

<style>
  .notice {
    margin-bottom: 12px;
  }

  .toolbar {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 10px;
  }

  .spacer {
    flex: 1;
  }

  .count {
    min-width: 40px;
    color: var(--text-2);
    font-size: var(--text-sm);
    text-align: center;
  }

  .slot {
    gap: 8px;
    padding-top: 8px;
    padding-bottom: 8px;
  }

  .slot .no {
    width: 56px;
    flex: none;
    color: var(--text-2);
    font-size: var(--text-md);
  }

  .slot .input {
    flex: 1;
    min-height: 38px;
    padding: 0 8px;
    text-align: center;
  }

  .dash {
    color: var(--text-3);
  }

  .warn {
    color: var(--danger);
  }

  .save {
    margin-top: 16px;
  }
</style>
