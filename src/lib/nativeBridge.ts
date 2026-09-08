declare global {
  interface Window {
    LumaNative?: { request(id: string, command: string, payload: string): void };
    __lumaNativeResolve?: (id: string, ok: boolean, payload: string) => void;
  }
}

type Pending = {
  resolve: (value: unknown) => void;
  reject: (reason?: unknown) => void;
  timer: ReturnType<typeof setTimeout>;
};

const pending = new Map<string, Pending>();
let requestSeq = 0;

window.__lumaNativeResolve = (id, ok, payload) => {
  const item = pending.get(id);
  if (!item) return;
  pending.delete(id);
  clearTimeout(item.timer);
  let value: unknown = null;
  try { value = JSON.parse(payload); }
  catch { value = payload; }
  if (ok) item.resolve(value);
  else {
    const message = typeof value === 'object' && value && 'message' in value
      ? String((value as { message: unknown }).message)
      : String(value);
    item.reject(new Error(message));
  }
};

export function invokeNative<T>(command: string, args: Record<string, unknown> = {}, timeoutMs = 45_000): Promise<T> {
  const native = window.LumaNative;
  if (!native) return Promise.reject(new Error('当前环境没有 LumaSchedule Android Native Bridge。'));
  const id = `${Date.now().toString(36)}-${(++requestSeq).toString(36)}`;
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => {
      pending.delete(id);
      reject(new Error(`原生操作超时：${command}`));
    }, timeoutMs);
    pending.set(id, { resolve: resolve as (value: unknown) => void, reject, timer });
    native.request(id, command, JSON.stringify(args));
  });
}

export function unwrap<T>(value: { value: T } | T): T {
  return typeof value === 'object' && value !== null && 'value' in value
    ? (value as { value: T }).value
    : value as T;
}
