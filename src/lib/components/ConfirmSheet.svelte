<script lang="ts">
  import { AlertTriangle, X } from 'lucide-svelte';
  import { createEventDispatcher } from 'svelte';

  export let open = false;
  export let title = '确认操作';
  export let message = '';
  export let confirmText = '确认';
  export let cancelText = '取消';
  export let danger = false;
  export let busy = false;

  const dispatch = createEventDispatcher<{ confirm: void; cancel: void }>();

  function cancel() {
    if (!busy) dispatch('cancel');
  }
</script>

{#if open}
  <div class="confirm-backdrop" role="presentation" on:click={(event) => { if (event.currentTarget === event.target) cancel(); }}>
    <section class="confirm-sheet glass-panel refract" role="dialog" aria-modal="true" aria-label={title}>
      <div class="confirm-head">
        <span class:danger><AlertTriangle size={19} /></span>
        <div><small>需要确认</small><h2>{title}</h2></div>
        <button on:click={cancel} disabled={busy} aria-label="关闭"><X size={18} /></button>
      </div>
      <p>{message}</p>
      <div class="confirm-actions">
        <button class="cancel" on:click={cancel} disabled={busy}>{cancelText}</button>
        <button class:danger class="confirm" on:click={() => dispatch('confirm')} disabled={busy}>{busy ? '正在处理…' : confirmText}</button>
      </div>
    </section>
  </div>
{/if}

<style>
  .confirm-backdrop { position: fixed; inset: 0; z-index: 180; display: flex; align-items: flex-end; justify-content: center; background: rgba(18,18,24,.24); -webkit-backdrop-filter: blur(5px); backdrop-filter: blur(5px); }
  .confirm-sheet { width: min(520px,100%); border-radius: 28px 28px 0 0; padding: 16px 18px calc(18px + env(safe-area-inset-bottom)); }
  .confirm-head { display: grid; grid-template-columns: 42px 1fr 38px; gap: 11px; align-items: center; }
  .confirm-head > span { width: 42px; height: 42px; border-radius: 14px; display: grid; place-items: center; color: #6b64d8; background: rgba(107,100,216,.09); }
  .confirm-head > span.danger { color: #c44d5e; background: rgba(220,70,84,.09); }
  .confirm-head small { display:block; font-size: 10px; color: rgba(60,60,67,.52); }
  .confirm-head h2 { margin: 2px 0 0; font-size: 20px; letter-spacing: -.03em; }
  .confirm-head > button { width:38px; height:38px; border:0; border-radius:19px; background:rgba(118,118,128,.10); color:rgba(60,60,67,.62); display:grid; place-items:center; }
  .confirm-sheet > p { margin: 15px 2px 18px; font-size: 13px; line-height: 1.55; color: rgba(60,60,67,.70); }
  .confirm-actions { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
  .confirm-actions button { min-height: 46px; border: 0; border-radius: 15px; font-weight: 650; }
  .confirm-actions .cancel { background: rgba(118,118,128,.10); color: inherit; }
  .confirm-actions .confirm { background: #625cd0; color: white; }
  .confirm-actions .confirm.danger { background: #c54f60; }
</style>
