<script lang="ts">
  import { TriangleAlert } from 'lucide-svelte';
  import { confirmHost } from '../state/confirm.svelte';
  import Sheet from './Sheet.svelte';

  const current = $derived(confirmHost.current);
</script>

<Sheet open={!!current} title={current?.title ?? ''} onclose={() => confirmHost.settle(false)}>
  {#if current}
    <div class="confirm">
      {#if current.danger}<span class="icon"><TriangleAlert size={20} /></span>{/if}
      {#if current.message}<p>{current.message}</p>{/if}
    </div>
  {/if}
  {#snippet footer()}
    <button class="btn btn-lg" style="flex:1" type="button" onclick={() => confirmHost.settle(false)}>{current?.cancelText ?? '取消'}</button>
    <button
      class="btn btn-lg {current?.danger ? 'btn-danger' : 'btn-primary'}"
      style="flex:1"
      type="button"
      onclick={() => confirmHost.settle(true)}>{current?.confirmText ?? '确定'}</button
    >
  {/snippet}
</Sheet>

<style>
  .confirm {
    display: flex;
    align-items: flex-start;
    gap: 12px;
    padding: 4px 0 4px;
  }

  .icon {
    display: grid;
    place-items: center;
    width: 36px;
    height: 36px;
    flex: none;
    border-radius: 50%;
    background: var(--danger-soft);
    color: var(--danger);
  }

  p {
    color: var(--text-2);
    font-size: var(--text-md);
    line-height: 1.65;
  }
</style>
