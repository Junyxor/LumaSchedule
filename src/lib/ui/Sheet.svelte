<script lang="ts">
  import { X } from 'lucide-svelte';
  import type { Snippet } from 'svelte';
  import { cubicOut } from 'svelte/easing';
  import { fade, fly } from 'svelte/transition';
  import { useBack } from '../state/back.svelte';
  import { lockScroll, portal } from './dom';

  interface Props {
    open: boolean;
    title?: string;
    subtitle?: string;
    /** auto: grows with content · tall: fixed 88% height · full: almost full screen */
    size?: 'auto' | 'tall' | 'full';
    /** When false the sheet ignores backdrop taps, drags and back presses (e.g. while saving). */
    dismissible?: boolean;
    onclose: () => void;
    children: Snippet;
    footer?: Snippet;
    leading?: Snippet;
    actions?: Snippet;
  }

  let { open, title = '', subtitle = '', size = 'auto', dismissible = true, onclose, children, footer, leading, actions }: Props = $props();

  const titleId = `sheet-${Math.random().toString(36).slice(2, 9)}`;
  let panel = $state<HTMLElement>();
  let dragOffset = $state(0);
  let dragging = $state(false);
  let dragStart = 0;
  let pointerId: number | null = null;

  function requestClose() {
    if (dismissible) onclose();
  }

  useBack(() => open, requestClose);

  $effect(() => {
    if (!open) return;
    const release = lockScroll();
    const previous = document.activeElement as HTMLElement | null;
    queueMicrotask(() => panel?.focus({ preventScroll: true }));
    return () => {
      release();
      previous?.focus?.({ preventScroll: true });
    };
  });

  $effect(() => {
    if (!open) dragOffset = 0;
  });

  function beginDrag(event: PointerEvent) {
    if (!dismissible || (event.pointerType === 'mouse' && event.button !== 0)) return;
    if ((event.target as HTMLElement).closest('button, a, input, select, textarea')) return;
    pointerId = event.pointerId;
    dragStart = event.clientY;
    dragging = true;
    (event.currentTarget as HTMLElement).setPointerCapture?.(event.pointerId);
  }

  function moveDrag(event: PointerEvent) {
    if (!dragging || event.pointerId !== pointerId) return;
    dragOffset = Math.max(0, event.clientY - dragStart);
  }

  function endDrag(event: PointerEvent) {
    if (!dragging || event.pointerId !== pointerId) return;
    dragging = false;
    pointerId = null;
    if (dragOffset > Math.min(140, window.innerHeight * 0.18)) requestClose();
    else dragOffset = 0;
  }

  function onkeydown(event: KeyboardEvent) {
    if (event.key === 'Escape') {
      event.stopPropagation();
      requestClose();
    }
  }
</script>

{#if open}
  <div class="sheet-root" use:portal>
    <!-- svelte-ignore a11y_click_events_have_key_events -->
    <!-- svelte-ignore a11y_no_static_element_interactions -->
    <div class="sheet-backdrop" transition:fade={{ duration: 160 }} onclick={requestClose}></div>
    <div
      class="sheet {size}"
      class:dragging
      class:has-footer={!!footer}
      role="dialog"
      aria-modal="true"
      aria-labelledby={title ? titleId : undefined}
      tabindex="-1"
      bind:this={panel}
      style:top={dragOffset ? `${dragOffset}px` : undefined}
      transition:fly={{ y: 56, duration: 240, easing: cubicOut, opacity: 0.4 }}
      {onkeydown}
    >
      <!-- Dragging the header down is a touch shortcut; Escape, back and the close button cover other inputs. -->
      <!-- svelte-ignore a11y_no_static_element_interactions -->
      <header class="sheet-head" onpointerdown={beginDrag} onpointermove={moveDrag} onpointerup={endDrag} onpointercancel={endDrag}>
        <span class="grabber" aria-hidden="true"></span>
        <div class="head-row">
          {@render leading?.()}
          <div class="titles">
            {#if title}<h2 id={titleId}>{title}</h2>{/if}
            {#if subtitle}<p>{subtitle}</p>{/if}
          </div>
          {@render actions?.()}
          {#if dismissible}
            <button class="icon-btn filled close" type="button" aria-label="关闭" onclick={onclose}><X size={18} /></button>
          {/if}
        </div>
      </header>
      <div class="sheet-body">
        {@render children()}
      </div>
      {#if footer}
        <footer class="sheet-foot">{@render footer()}</footer>
      {/if}
    </div>
  </div>
{/if}

<style>
  .sheet-root {
    position: fixed;
    inset: 0;
    z-index: 200;
    display: flex;
    align-items: flex-end;
    justify-content: center;
  }

  .sheet-backdrop {
    position: absolute;
    inset: 0;
    background: var(--overlay);
  }

  .sheet {
    position: relative;
    display: flex;
    flex-direction: column;
    width: 100%;
    max-width: 640px;
    max-height: calc(100vh - var(--inset-top) - 24px);
    max-height: calc(100dvh - var(--inset-top) - 24px);
    overflow: hidden;
    border-radius: var(--radius-xl) var(--radius-xl) 0 0;
    background: var(--surface-raised);
    box-shadow: var(--shadow-lg);
    outline: none;
    transition: top 0.2s var(--ease);
  }

  .sheet.dragging {
    transition: none;
  }

  .sheet.tall {
    height: calc(100vh - var(--inset-top) - 56px);
    height: calc(100dvh - var(--inset-top) - 56px);
  }

  .sheet.full {
    height: calc(100vh - var(--inset-top) - 10px);
    height: calc(100dvh - var(--inset-top) - 10px);
  }

  .sheet-head {
    flex: none;
    padding: 8px 12px 10px 20px;
    touch-action: none;
  }

  .grabber {
    display: block;
    width: 38px;
    height: 4px;
    margin: 0 auto 8px;
    border-radius: 2px;
    background: var(--fill-strong);
  }

  .head-row {
    display: flex;
    align-items: center;
    gap: 8px;
    min-height: 40px;
  }

  .titles {
    flex: 1;
    min-width: 0;
  }

  .titles h2 {
    overflow: hidden;
    font-size: var(--text-lg);
    font-weight: 650;
    line-height: 1.3;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .titles p {
    margin-top: 1px;
    overflow: hidden;
    color: var(--text-2);
    font-size: var(--text-sm);
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .close {
    width: 34px;
    height: 34px;
  }

  .sheet-body {
    flex: 1 1 auto;
    min-height: 0;
    overflow-y: auto;
    overscroll-behavior: contain;
    padding: 2px 20px 20px;
  }

  .sheet:not(.has-footer) .sheet-body {
    padding-bottom: calc(20px + var(--inset-bottom));
  }

  .sheet-foot {
    flex: none;
    display: flex;
    gap: 10px;
    padding: 12px 20px calc(12px + var(--inset-bottom));
    border-top: 1px solid var(--border);
    background: var(--surface-raised);
  }

  @media (min-width: 720px) {
    .sheet-root {
      align-items: center;
      padding: 32px;
    }

    .sheet {
      border-radius: var(--radius-xl);
      max-height: min(760px, calc(100vh - 64px));
    }

    .sheet.tall,
    .sheet.full {
      height: min(760px, calc(100vh - 64px));
    }

    .sheet-foot {
      padding-bottom: 14px;
    }

    .grabber {
      display: none;
    }
  }
</style>
