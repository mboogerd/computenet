import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { SseClient } from '../src/sync/sse';
import type { ConnState } from '../src/sync/sse';

/** Minimal EventSource stand-in so we can drive the reconnect state machine. */
class MockEventSource {
  static last: MockEventSource | undefined;
  onopen: ((e: Event) => void) | null = null;
  onmessage: ((e: MessageEvent) => void) | null = null;
  onerror: ((e: Event) => void) | null = null;
  closed = false;
  constructor(public url: string) {
    MockEventSource.last = this;
  }
  close() {
    this.closed = true;
  }
  open() {
    this.onopen?.(new Event('open'));
  }
  message(data: string) {
    this.onmessage?.({ data } as MessageEvent);
  }
  error() {
    this.onerror?.(new Event('error'));
  }
}

describe('SseClient reconnect state machine', () => {
  let client: SseClient;
  beforeEach(() => {
    (globalThis as unknown as { EventSource: unknown }).EventSource = MockEventSource;
    (globalThis as unknown as { fetch: unknown }).fetch = vi.fn(); // fallback timer must not hit network
  });
  afterEach(() => {
    client.stop();
  });

  it('flows connecting → live and marks the first post-reconnect snapshot resync', () => {
    const states: ConnState[] = [];
    const snaps: { count: number; resync: boolean }[] = [];
    client = new SseClient('/events');
    client.onState((s) => states.push(s));
    client.start((dtos, resync) => snaps.push({ count: dtos.length, resync }));

    const es = MockEventSource.last!;
    es.open();
    es.message('[]'); // initial load
    es.message('[{"ref":"a","kind":"CLAIM","credence":0.5}]'); // a normal update

    // connection drops and EventSource auto-reconnects
    es.error();
    es.open();
    es.message('[{"ref":"a","kind":"CLAIM","credence":0.6}]'); // catch-up snapshot

    expect(states).toEqual(['connecting', 'live', 'reconnecting', 'live']);
    expect(snaps.map((s) => s.resync)).toEqual([false, false, true]);
    // and the snapshot AFTER the catch-up is no longer flagged
    es.message('[]');
    expect(snaps.at(-1)!.resync).toBe(false);
  });
});
