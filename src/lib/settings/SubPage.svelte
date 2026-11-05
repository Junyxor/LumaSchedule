<script lang="ts">
  import { ArrowLeft } from 'lucide-svelte';
  import type { Snippet } from 'svelte';

  interface Props {
    title: string;
    description?: string;
    onback: () => void;
    children: Snippet;
  }

  let { title, description = '', onback, children }: Props = $props();
</script>

<section class="page sub">
  <header class="sub-header">
    <button class="icon-btn filled" type="button" aria-label="返回设置" onclick={onback}><ArrowLeft size={20} /></button>
    <div>
      <h1>{title}</h1>
      {#if description}<p>{description}</p>{/if}
    </div>
  </header>
  {@render children()}
</section>

<style>
  .sub-header {
    display: flex;
    align-items: center;
    gap: 12px;
    min-height: 52px;
    margin-bottom: 16px;
  }

  .sub-header div {
    min-width: 0;
  }

  h1 {
    font-size: var(--text-xl);
    font-weight: 700;
    line-height: 1.25;
  }

  p {
    color: var(--text-2);
    font-size: var(--text-sm);
  }
</style>
