<script lang="ts">
  import type { Snippet } from 'svelte';
  import type { IconComponent } from './dom';

  interface Props {
    icon?: IconComponent;
    title: string;
    message?: string;
    compact?: boolean;
    actions?: Snippet;
  }

  let { icon, title, message = '', compact = false, actions }: Props = $props();
</script>

<div class="empty" class:compact>
  {#if icon}
    {@const Icon = icon}
    <span class="icon"><Icon size={compact ? 20 : 24} /></span>
  {/if}
  <h3>{title}</h3>
  {#if message}<p>{message}</p>{/if}
  {#if actions}<div class="actions">{@render actions()}</div>{/if}
</div>

<style>
  .empty {
    display: grid;
    justify-items: center;
    gap: 6px;
    padding: 28px 20px;
    text-align: center;
  }

  .empty.compact {
    padding: 18px 12px;
  }

  .icon {
    display: grid;
    place-items: center;
    width: 48px;
    height: 48px;
    margin-bottom: 6px;
    border-radius: 50%;
    background: var(--accent-soft);
    color: var(--accent);
  }

  .compact .icon {
    width: 40px;
    height: 40px;
    margin-bottom: 2px;
  }

  h3 {
    font-size: var(--text-base);
    font-weight: 650;
  }

  p {
    max-width: 340px;
    color: var(--text-2);
    font-size: var(--text-md);
    line-height: 1.6;
  }

  .actions {
    display: flex;
    flex-wrap: wrap;
    justify-content: center;
    gap: 8px;
    margin-top: 10px;
  }
</style>
