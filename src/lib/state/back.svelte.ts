import { untrack } from 'svelte';

/**
 * A small stack of "back" handlers (open sheets, settings sub-pages …).
 * Android's system back gesture and the Escape key pop the top entry.
 */

interface Entry {
  id: number;
  handler: () => void;
}

class BackStack {
  #entries = $state<Entry[]>([]);
  #seq = 0;

  get depth() {
    return this.#entries.length;
  }

  /**
   * Registers a handler. Reads of the stack are untracked so callers can push
   * from inside an effect without making that effect depend on the stack.
   */
  push(handler: () => void) {
    const id = ++this.#seq;
    untrack(() => {
      this.#entries = [...this.#entries, { id, handler }];
    });
    return () => {
      untrack(() => {
        this.#entries = this.#entries.filter((entry) => entry.id !== id);
      });
    };
  }

  /**
   * Runs the newest handler and reports that the event was consumed. The owner
   * removes its entry when it actually closes, so a busy sheet that ignores the
   * request keeps swallowing back presses instead of leaking them to the page.
   */
  handle() {
    const top = untrack(() => this.#entries[this.#entries.length - 1]);
    if (!top) return false;
    top.handler();
    return true;
  }
}

export const back = new BackStack();

/** Registers `onBack` while `active()` is true. Call during component initialisation. */
export function useBack(active: () => boolean, onBack: () => void) {
  $effect(() => {
    if (!active()) return;
    return back.push(() => untrack(onBack));
  });
}
