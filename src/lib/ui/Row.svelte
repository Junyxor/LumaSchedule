<script lang="ts">
  import { ChevronRight, ExternalLink } from 'lucide-svelte';
  import type { Snippet } from 'svelte';
  import type { IconComponent } from './dom';

  interface Props {
    title: string;
    sub?: string;
    value?: string;
    icon?: IconComponent;
    href?: string;
    onclick?: () => void;
    /** Renders the row as an accessible switch. */
    toggle?: boolean;
    checked?: boolean;
    chevron?: boolean;
    disabled?: boolean;
    danger?: boolean;
    busy?: boolean;
    trailing?: Snippet;
  }

  let { title, sub = '', value = '', icon, href, onclick, toggle = false, checked = false, chevron, disabled = false, danger = false, busy = false, trailing }: Props = $props();

  const showChevron = $derived(chevron ?? (!!onclick && !toggle && !trailing));
</script>

{#snippet body()}
  {#if icon}
    {@const Icon = icon}
    <span class="row-icon"><Icon size={17} strokeWidth={2} /></span>
  {/if}
  <span class="row-body">
    <span class="row-title">{title}</span>
    {#if sub}<span class="row-sub">{sub}</span>{/if}
  </span>
  {#if value}<span class="row-value">{value}</span>{/if}
  {#if busy}<span class="spinner"></span>{/if}
  {@render trailing?.()}
  {#if toggle}
    <span class="switch" class:on={checked} aria-hidden="true"><i></i></span>
  {:else if href}
    <ExternalLink class="row-chevron" size={16} />
  {:else if showChevron}
    <ChevronRight class="row-chevron" size={18} />
  {/if}
{/snippet}

{#if href}
  <a class="list-row" class:has-icon={!!icon} class:danger {href} rel="noreferrer">{@render body()}</a>
{:else if toggle}
  <button class="list-row" class:has-icon={!!icon} type="button" role="switch" aria-checked={checked} disabled={disabled || busy} onclick={() => onclick?.()}>
    {@render body()}
  </button>
{:else if onclick}
  <button class="list-row" class:has-icon={!!icon} class:danger type="button" disabled={disabled || busy} onclick={() => onclick?.()}>{@render body()}</button>
{:else}
  <div class="list-row" class:has-icon={!!icon} class:danger>{@render body()}</div>
{/if}

<style>
  .switch {
    position: relative;
    width: 44px;
    height: 26px;
    flex: none;
    border-radius: 13px;
    background: var(--fill-strong);
    transition: background-color 0.18s;
  }

  .switch i {
    position: absolute;
    top: 3px;
    left: 3px;
    width: 20px;
    height: 20px;
    border-radius: 50%;
    background: #fff;
    box-shadow: 0 1px 3px rgba(0, 0, 0, 0.22);
    transition: transform 0.2s var(--ease);
  }

  .switch.on {
    background: var(--accent);
  }

  .switch.on i {
    transform: translateX(18px);
  }

  a.list-row {
    color: inherit;
  }
</style>
