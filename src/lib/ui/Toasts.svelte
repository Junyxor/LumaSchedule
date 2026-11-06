<script lang="ts">
  import { CircleAlert, CircleCheck, Info } from 'lucide-svelte';
  import { fly } from 'svelte/transition';
  import { toast } from '../state/toast.svelte';
</script>

<div class="toasts" aria-live="polite">
  {#each toast.items as item (item.id)}
    <button class="toast {item.tone}" type="button" transition:fly={{ y: 16, duration: 200 }} onclick={() => toast.dismiss(item.id)}>
      {#if item.tone === 'success'}<CircleCheck size={18} />{:else if item.tone === 'error'}<CircleAlert size={18} />{:else}<Info size={18} />{/if}
      <span>{item.message}</span>
    </button>
  {/each}
</div>

<style>
  .toasts {
    position: fixed;
    right: calc(var(--inset-right) + 16px);
    bottom: calc(var(--tabbar-height) + var(--inset-bottom) + 12px);
    left: calc(var(--inset-left) + 16px);
    z-index: 300;
    display: grid;
    justify-items: center;
    gap: 8px;
    pointer-events: none;
  }

  .toast {
    display: flex;
    align-items: flex-start;
    gap: 10px;
    max-width: 520px;
    padding: 11px 16px;
    border: 0;
    border-radius: var(--radius-md);
    background: #23252f;
    color: #f4f5f8;
    box-shadow: var(--shadow-md);
    font-size: var(--text-md);
    line-height: 1.5;
    text-align: left;
    pointer-events: auto;
  }

  :global([data-theme='dark']) .toast {
    background: #f1f2f6;
    color: #1a1c24;
  }

  .toast :global(svg) {
    margin-top: 2px;
    color: #a9a6ff;
  }

  .toast.success :global(svg) {
    color: #4fd39a;
  }

  .toast.error :global(svg) {
    color: #ff7a8f;
  }

  :global([data-theme='dark']) .toast :global(svg) {
    color: var(--accent);
  }

  :global([data-theme='dark']) .toast.success :global(svg) {
    color: #1c8f5a;
  }

  :global([data-theme='dark']) .toast.error :global(svg) {
    color: #cf3f58;
  }

  @media (min-width: 900px) {
    .toasts {
      bottom: 24px;
    }
  }
</style>
