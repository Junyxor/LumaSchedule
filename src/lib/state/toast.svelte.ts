export type ToastTone = 'info' | 'success' | 'error';

export interface ToastItem {
  id: number;
  message: string;
  tone: ToastTone;
}

class Toasts {
  items = $state<ToastItem[]>([]);
  #seq = 0;

  show(message: string, tone: ToastTone = 'info', duration = 3200) {
    const text = message.trim();
    if (!text) return;
    const id = ++this.#seq;
    // Keep the list short; the newest message matters most.
    this.items = [...this.items.slice(-2), { id, message: text, tone }];
    setTimeout(() => this.dismiss(id), tone === 'error' ? Math.max(duration, 5200) : duration);
  }

  success(message: string) {
    this.show(message, 'success');
  }

  error(message: string) {
    this.show(message, 'error');
  }

  dismiss(id: number) {
    this.items = this.items.filter((item) => item.id !== id);
  }
}

export const toast = new Toasts();
