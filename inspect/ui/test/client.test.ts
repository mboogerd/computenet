import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { InspectEvent, TopologySnapshot } from '../src/api/types';
import { TopologyClient, type ConnState } from '../src/sync/client';

/** Minimal EventSource stand-in — same technique as demo/agora/ui's
 *  test/sse.test.ts MockEventSource, driving the reconnect state machine
 *  without a real network/browser EventSource. */
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
  message(data: unknown) {
    this.onmessage?.({ data: JSON.stringify(data) } as MessageEvent);
  }
  error() {
    this.onerror?.(new Event('error'));
  }
}

function snapshot(seq: number): TopologySnapshot {
  return { seq, nodes: [], edges: [] };
}

function evt(seq: number, kind: string = 'lifecycle'): InspectEvent {
  return { seq, kind, payload: { ref: 'a', lifecycle: 'HOT', generation: 0 } } as InspectEvent;
}

describe('TopologyClient', () => {
  let fetchMock: ReturnType<typeof vi.fn>;
  let onSnapshot: ReturnType<typeof vi.fn>;
  let onEvent: ReturnType<typeof vi.fn>;
  let onState: ReturnType<typeof vi.fn>;
  let client: TopologyClient;

  beforeEach(() => {
    (globalThis as unknown as { EventSource: unknown }).EventSource = MockEventSource;
    fetchMock = vi.fn();
    (globalThis as unknown as { fetch: unknown }).fetch = fetchMock;
    onSnapshot = vi.fn();
    onEvent = vi.fn();
    onState = vi.fn();
    client = new TopologyClient({ onSnapshot, onEvent, onState });
  });

  afterEach(() => {
    client.stop();
  });

  function mockFetchOnce(seq: number) {
    fetchMock.mockResolvedValueOnce({ ok: true, json: async () => snapshot(seq) });
  }

  it('fetches the initial snapshot before opening the event stream', async () => {
    mockFetchOnce(10);
    await client.start();
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledWith('/api/inspect/topology');
    expect(onSnapshot).toHaveBeenCalledWith(snapshot(10));
    expect(MockEventSource.last?.url).toBe('/api/inspect/events');
  });

  it('applies in-order events and ignores stale/duplicate seqs', async () => {
    mockFetchOnce(10);
    await client.start();
    const es = MockEventSource.last!;
    es.open();

    es.message(evt(11));
    expect(onEvent).toHaveBeenCalledTimes(1);

    es.message(evt(11)); // duplicate — already applied
    es.message(evt(10)); // stale — predates the snapshot
    expect(onEvent).toHaveBeenCalledTimes(1);
  });

  it('refetches the snapshot on a seq gap, without applying the gap-revealing event', async () => {
    mockFetchOnce(10);
    await client.start();
    const es = MockEventSource.last!;
    es.open();

    mockFetchOnce(20); // the resync snapshot the gap triggers
    es.message(evt(13)); // expected 11 — a gap
    expect(onEvent).not.toHaveBeenCalled();

    // wait on onSnapshot itself, not just the fetch call count: fetch() is
    // invoked synchronously but its .json() continuation (and the resulting
    // onSnapshot call) only lands after a couple of microtask hops.
    await vi.waitFor(() => expect(onSnapshot).toHaveBeenCalledTimes(2));
    expect(onSnapshot).toHaveBeenLastCalledWith(snapshot(20));

    // subsequent events are filtered against the new snapshot's seq
    es.message(evt(21));
    expect(onEvent).toHaveBeenCalledTimes(1);
  });

  it('refetches on reconnect after a disconnect (native EventSource auto-retries)', async () => {
    mockFetchOnce(10);
    await client.start();
    const es = MockEventSource.last!;
    es.open();
    es.message(evt(11));
    expect(onState.mock.calls.map((c) => c[0])).toEqual<ConnState[]>(['connecting', 'live']);

    es.error();
    expect(onState.mock.calls.at(-1)?.[0]).toBe('reconnecting');

    mockFetchOnce(50); // the resync snapshot the reconnect triggers
    es.open(); // EventSource's own auto-reconnect firing onopen again
    await vi.waitFor(() => expect(onSnapshot).toHaveBeenCalledTimes(2));
    expect(onSnapshot).toHaveBeenLastCalledWith(snapshot(50));
    expect(onState.mock.calls.at(-1)?.[0]).toBe('live');

    es.message(evt(51));
    expect(onEvent).toHaveBeenCalledTimes(2); // seq 11, then seq 51
  });

  it('advances lastSeq for an unrecognized (future-milestone) event kind without erroring', async () => {
    mockFetchOnce(10);
    await client.start();
    const es = MockEventSource.last!;
    es.open();

    es.message(evt(11, 'state.summary')); // M1 kind — additive evolution, not yet understood
    expect(onEvent).not.toHaveBeenCalled();

    es.message(evt(12, 'lifecycle')); // must be accepted, not treated as a gap
    expect(onEvent).toHaveBeenCalledTimes(1);
  });
});
