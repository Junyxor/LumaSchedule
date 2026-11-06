/**
 * Promise wrapper around the Android `LumaNative` JavascriptInterface.
 *
 * Native code receives `(id, command, payloadJson)` on a worker thread and
 * answers through `window.__lumaNativeResolve(id, ok, payloadJson)`.
 */

export interface LumaNativeInterface {
  request(id: string, command: string, payload: string): void;
  /** Tells Android whether the web UI wants to handle the system back gesture. */
  setBackEnabled?(enabled: boolean): void;
  /** Switches status/navigation bar icons between light and dark. */
  setSystemBarsDark?(dark: boolean): void;
  /** Android's system dark mode. More reliable than prefers-color-scheme inside WebView. */
  isSystemDark?(): boolean;
  /**
   * System bar insets in CSS pixels plus the WebView text zoom in percent, as JSON:
   * {"top":24,"right":0,"bottom":16,"left":0,"textZoom":115}.
   */
  getInsets?(): string;
}

/**
 * Applies Android's system bar insets and text zoom before the first paint; later
 * changes are pushed by Kotlin. `--text-zoom` lets dense layouts (the week grid)
 * follow the system font size less than the rest of the UI.
 */
export function applyNativeInsets() {
  try {
    const raw = window.LumaNative?.getInsets?.();
    if (!raw) return;
    const insets = JSON.parse(raw) as Record<'top' | 'right' | 'bottom' | 'left', number> & { textZoom?: number };
    const style = document.documentElement.style;
    for (const side of ['top', 'right', 'bottom', 'left'] as const) {
      if (Number.isFinite(insets[side])) style.setProperty(`--native-inset-${side}`, `${insets[side]}px`);
    }
    if (typeof insets.textZoom === 'number' && insets.textZoom > 0) style.setProperty('--text-zoom', String(insets.textZoom / 100));
  } catch {
    // env(safe-area-inset-*) still applies.
  }
}

export function systemPrefersDark() {
  try {
    const native = window.LumaNative?.isSystemDark?.();
    if (typeof native === 'boolean') return native;
  } catch {
    // Fall through to the media query.
  }
  return window.matchMedia('(prefers-color-scheme: dark)').matches;
}

declare global {
  interface Window {
    LumaNative?: LumaNativeInterface;
    __lumaNativeResolve?: (id: string, ok: boolean, payload: string) => void;
    /** Called by Android on system back. Returns true when the UI consumed it. */
    __lumaBack?: () => boolean;
  }
}

type Pending = {
  resolve: (value: unknown) => void;
  reject: (reason?: unknown) => void;
  timer: ReturnType<typeof setTimeout>;
};

const pending = new Map<string, Pending>();
let requestSeq = 0;

export class NativeError extends Error {
  readonly command: string;
  constructor(command: string, message: string) {
    super(message);
    this.name = 'NativeError';
    this.command = command;
  }
}

window.__lumaNativeResolve = (id, ok, payload) => {
  const item = pending.get(id);
  if (!item) return;
  pending.delete(id);
  clearTimeout(item.timer);
  let value: unknown = null;
  try {
    value = JSON.parse(payload);
  } catch {
    value = payload;
  }
  if (ok) {
    item.resolve(value);
    return;
  }
  const message =
    typeof value === 'object' && value !== null && 'message' in value
      ? String((value as { message: unknown }).message)
      : String(value);
  item.reject(new Error(message || '原生操作失败'));
};

export function hasNative() {
  return typeof window !== 'undefined' && !!window.LumaNative;
}

export function invoke<T>(command: string, args: Record<string, unknown> = {}, timeoutMs = 45_000): Promise<T> {
  const native = window.LumaNative;
  if (!native) {
    return Promise.reject(new NativeError(command, '当前环境不是 LumaSchedule 应用，无法访问本地数据。'));
  }
  const id = `${Date.now().toString(36)}-${(++requestSeq).toString(36)}`;
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => {
      pending.delete(id);
      reject(new NativeError(command, `操作超时（${command}），请重试。`));
    }, timeoutMs);
    pending.set(id, { resolve: resolve as (value: unknown) => void, reject, timer });
    try {
      native.request(id, command, JSON.stringify(args));
    } catch (error) {
      clearTimeout(timer);
      pending.delete(id);
      reject(error);
    }
  });
}

export function unwrap<T>(value: { value: T } | T): T {
  return typeof value === 'object' && value !== null && 'value' in value ? (value as { value: T }).value : (value as T);
}

export function errorMessage(error: unknown): string {
  if (error instanceof Error) return error.message;
  return String(error ?? '未知错误');
}
