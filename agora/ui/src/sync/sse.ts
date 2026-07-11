import type { NodeDto } from '../api/types';

export type ConnState = 'connecting' | 'live' | 'reconnecting';

/** Feeds the store from the backend's full-snapshot SSE. The server pushes a
 *  snapshot immediately on every (re)connect, so that message IS both the
 *  initial load and the reconnect reconciliation — no separate refetch, no
 *  merge. EventSource auto-retries; the only extra rule is flagging the first
 *  snapshot after an error as `resync` so it doesn't strobe pulses/ticker.
 *
 *  This is the seam the future per-cell subscription API swaps in behind. */
export class SseClient {
  private es?: EventSource;
  private resyncPending = false;
  private hadError = false;
  private gotFirst = false;
  private fallbackTimer?: ReturnType<typeof setTimeout>;
  private stateFn?: (s: ConnState) => void;

  constructor(private url = '/events') {}

  onState(fn: (s: ConnState) => void): void {
    this.stateFn = fn;
  }

  start(onSnapshot: (dtos: NodeDto[], resync: boolean) => void): void {
    this.set('connecting');
    const es = new EventSource(this.url);
    this.es = es;

    // Degraded fallback: if no SSE frame arrives within 5s, pull /graph once
    // so the app still paints. SSE keeps trying in the background.
    this.fallbackTimer = setTimeout(async () => {
      if (this.gotFirst) return;
      try {
        const r = await fetch('/graph');
        if (r.ok && !this.gotFirst) onSnapshot((await r.json()) as NodeDto[], false);
      } catch {
        /* SSE will keep retrying */
      }
    }, 5000);

    es.onopen = () => {
      if (this.hadError) this.resyncPending = true; // next message is a catch-up
      this.hadError = false;
      this.set('live');
    };
    es.onmessage = (e) => {
      this.gotFirst = true;
      clearTimeout(this.fallbackTimer);
      const dtos = JSON.parse(e.data) as NodeDto[];
      const resync = this.resyncPending;
      this.resyncPending = false;
      onSnapshot(dtos, resync);
    };
    es.onerror = () => {
      this.hadError = true;
      this.set('reconnecting'); // EventSource reconnects on its own
    };
  }

  stop(): void {
    clearTimeout(this.fallbackTimer);
    this.es?.close();
    this.es = undefined;
  }

  private set(s: ConnState): void {
    this.stateFn?.(s);
  }
}
