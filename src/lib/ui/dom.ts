import type { Calendar } from 'lucide-svelte';

/** Any lucide icon component. */
export type IconComponent = typeof Calendar;

/** Moves an element to <body> so fixed overlays escape transformed ancestors. */
export function portal(node: HTMLElement) {
  document.body.appendChild(node);
  return {
    destroy() {
      node.remove();
    }
  };
}

let scrollLocks = 0;

export function lockScroll() {
  if (scrollLocks++ === 0) document.body.classList.add('scroll-locked');
  let released = false;
  return () => {
    if (released) return;
    released = true;
    if (--scrollLocks === 0) document.body.classList.remove('scroll-locked');
  };
}
