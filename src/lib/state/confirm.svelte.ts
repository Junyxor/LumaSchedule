export interface ConfirmOptions {
  title: string;
  message?: string;
  confirmText?: string;
  cancelText?: string;
  danger?: boolean;
}

interface Pending extends ConfirmOptions {
  resolve: (value: boolean) => void;
}

class ConfirmHost {
  current = $state<Pending | null>(null);

  ask(options: ConfirmOptions) {
    this.current?.resolve(false);
    return new Promise<boolean>((resolve) => {
      this.current = { ...options, resolve };
    });
  }

  settle(value: boolean) {
    const pending = this.current;
    this.current = null;
    pending?.resolve(value);
  }
}

export const confirmHost = new ConfirmHost();

/** Asks the user to confirm an action in a bottom sheet. Resolves to true when confirmed. */
export function confirmAction(options: ConfirmOptions) {
  return confirmHost.ask(options);
}
