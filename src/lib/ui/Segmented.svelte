<script lang="ts" generics="T extends string | number">
  interface Option {
    value: T;
    label: string;
  }

  interface Props {
    options: Option[];
    value: T;
    onchange: (value: T) => void;
    label?: string;
    disabled?: boolean;
    size?: 'md' | 'sm';
  }

  let { options, value, onchange, label = '', disabled = false, size = 'md' }: Props = $props();
</script>

<div class="segmented {size}" role="radiogroup" aria-label={label}>
  {#each options as option (option.value)}
    <button
      type="button"
      role="radio"
      aria-checked={option.value === value}
      class:active={option.value === value}
      {disabled}
      onclick={() => onchange(option.value)}>{option.label}</button
    >
  {/each}
</div>

<style>
  .segmented {
    display: flex;
    gap: 2px;
    width: 100%;
    padding: 3px;
    border-radius: var(--radius-md);
    background: var(--fill);
  }

  button {
    flex: 1 1 0;
    min-width: 0;
    min-height: 36px;
    padding: 0 6px;
    border: 0;
    border-radius: 9px;
    background: transparent;
    color: var(--text-2);
    font-size: var(--text-md);
    font-weight: 550;
    white-space: nowrap;
    transition: background-color 0.15s, color 0.15s;
  }

  .sm button {
    min-height: 30px;
    font-size: var(--text-sm);
  }

  button.active {
    background: var(--surface);
    color: var(--text);
    box-shadow: var(--shadow-sm);
  }

  :global([data-theme='dark']) button.active {
    background: var(--fill-strong);
  }

  button:disabled {
    opacity: 0.45;
  }
</style>
