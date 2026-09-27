import { createSignal } from 'solid-js';

/** A transient message about a command that did not go through (agora's Toasts pattern). */
export interface Toast {
  id: number;
  msg: string;
}

const [toasts, setToasts] = createSignal<readonly Toast[]>([]);
export { toasts };

/** How long a toast stays up. */
export const TOAST_MS = 6000;
/** At most this many at once; the oldest goes first. */
const MAX = 3;
let next = 0;
const timers = new Map<number, ReturnType<typeof setTimeout>>();

export function dismiss(id: number): void {
  const timer = timers.get(id);
  if (timer !== undefined) clearTimeout(timer);
  timers.delete(id);
  setToasts((t) => t.filter((x) => x.id !== id));
}

export function toast(msg: string, ms = TOAST_MS): void {
  const id = ++next;
  const current = toasts();
  for (const dropped of current.slice(0, Math.max(0, current.length - (MAX - 1)))) dismiss(dropped.id);
  setToasts((t) => [...t.slice(-(MAX - 1)), { id, msg }]);
  timers.set(id, setTimeout(() => dismiss(id), ms));
}

/** "Couldn't pause this question — /question/pause failed: 503", the reason clipped to one line. */
export function failed(what: string, err: unknown): string {
  const reason = (err instanceof Error ? err.message : String(err)).replace(/\s+/g, ' ').trim();
  const short = reason.length > 120 ? `${reason.slice(0, 119)}…` : reason;
  return short ? `Couldn't ${what} — ${short}` : `Couldn't ${what}`;
}
