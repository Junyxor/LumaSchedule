export async function confirm(message: string, options?: { title?: string; kind?: string }) {
  const prefix = options?.title ? `${options.title}\n\n` : '';
  return window.confirm(`${prefix}${message}`);
}
