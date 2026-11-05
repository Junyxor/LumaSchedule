<script lang="ts">
  import { Check, Pencil, Plus, Trash2 } from 'lucide-svelte';
  import { onMount } from 'svelte';
  import { api } from '../native/api';
  import { errorMessage } from '../native/bridge';
  import { app } from '../state/app.svelte';
  import { confirmAction } from '../state/confirm.svelte';
  import { toast } from '../state/toast.svelte';
  import type { ScheduleSummary } from '../types';
  import Sheet from '../ui/Sheet.svelte';

  let schedules = $state.raw<ScheduleSummary[]>([]);
  let loading = $state(true);
  let busy = $state(false);
  let renaming = $state.raw<ScheduleSummary | null>(null);
  let creating = $state(false);
  let nameInput = $state('');

  onMount(() => void load());

  async function load() {
    loading = true;
    try {
      schedules = await api.listSchedules();
    } catch (error) {
      toast.error(errorMessage(error));
    } finally {
      loading = false;
    }
  }

  async function run(task: () => Promise<void>) {
    if (busy) return;
    busy = true;
    try {
      await task();
      await load();
      await app.reload();
    } catch (error) {
      toast.error(errorMessage(error));
    } finally {
      busy = false;
    }
  }

  const activate = (item: ScheduleSummary) =>
    run(async () => {
      if (item.active) return;
      await api.activateSchedule(item.id);
      toast.success(`已切换到「${item.name}」`);
    });

  const remove = (item: ScheduleSummary) =>
    run(async () => {
      const ok = await confirmAction({
        title: `删除「${item.name}」？`,
        message: `这份课表的 ${item.courseCount} 门课程、${item.meetingCount} 个时段和作息时间都会被删除，成绩不受影响。`,
        confirmText: '删除',
        danger: true
      });
      if (!ok) return;
      await api.deleteSchedule(item.id);
      toast.success('已删除课表');
    });

  function openRename(item: ScheduleSummary) {
    renaming = item;
    nameInput = item.name;
  }

  function openCreate() {
    creating = true;
    nameInput = '';
  }

  const submitName = () =>
    run(async () => {
      const name = nameInput.trim();
      if (!name) throw new Error('名称不能为空。');
      if (renaming) {
        await api.renameSchedule(renaming.id, name);
        toast.success('已重命名');
      } else {
        await api.createSchedule(name);
        toast.success('已创建并切换到新课表');
      }
      renaming = null;
      creating = false;
    });
</script>

{#if loading}
  <div class="card loading"><span class="spinner"></span></div>
{:else if !schedules.length}
  <div class="card quiet">还没有课表。导入课表或添加课程后会自动创建。</div>
{:else}
  <div class="list">
    {#each schedules as item (item.id)}
      <div class="list-row schedule" class:active={item.active}>
        <button class="pick" type="button" onclick={() => activate(item)} disabled={busy} aria-pressed={item.active}>
          <span class="radio">{#if item.active}<Check size={14} strokeWidth={3} />{/if}</span>
          <span class="row-body">
            <span class="row-title">{item.name}</span>
            <span class="row-sub">{[item.termName && item.termName !== item.name ? item.termName : '', item.termStart ? `${item.termStart} 开学` : '未设置开学日期', `${item.courseCount} 门课`].filter(Boolean).join(' · ')}</span>
          </span>
        </button>
        <button class="icon-btn" type="button" aria-label="重命名" onclick={() => openRename(item)} disabled={busy}><Pencil size={17} /></button>
        <button class="icon-btn" type="button" aria-label="删除" onclick={() => remove(item)} disabled={busy}><Trash2 size={17} /></button>
      </div>
    {/each}
  </div>
{/if}
<p class="list-footer">今日、周课表、提醒和小组件都使用当前选中的课表。导入时选择「新建课表」会在这里多出一份。</p>

<button class="btn btn-tonal btn-block create" type="button" onclick={openCreate} disabled={busy}><Plus size={17} /> 新建空白课表</button>

<Sheet open={!!renaming || creating} title={renaming ? '重命名课表' : '新建空白课表'} onclose={() => ((renaming = null), (creating = false))} dismissible={!busy}>
  <label class="field">
    <span class="label">名称</span>
    <input class="input" bind:value={nameInput} maxlength="40" placeholder="例如 2026 秋季学期" />
  </label>
  {#snippet footer()}
    <button class="btn btn-lg btn-primary btn-block" type="button" onclick={submitName} disabled={busy}>{renaming ? '保存' : '创建'}</button>
  {/snippet}
</Sheet>

<style>
  .loading {
    display: grid;
    place-items: center;
    padding: 24px;
  }

  .quiet {
    color: var(--text-2);
  }

  .schedule {
    gap: 2px;
    padding-right: 8px;
  }

  .pick {
    display: flex;
    flex: 1;
    align-items: center;
    gap: 12px;
    min-width: 0;
    padding: 0;
    border: 0;
    background: none;
    text-align: left;
  }

  .radio {
    display: grid;
    place-items: center;
    width: 22px;
    height: 22px;
    flex: none;
    border: 1.5px solid var(--border-strong);
    border-radius: 50%;
    color: var(--on-accent);
  }

  .active .radio {
    border-color: var(--accent);
    background: var(--accent);
  }

  .create {
    margin-top: 16px;
  }
</style>
