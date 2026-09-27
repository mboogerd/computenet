import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { GraphDto } from '../src/api/types';
import type { ConnState } from '../src/sync/source';
import { LiveSource } from '../src/sync/sse';

const EMPTY: GraphDto = { questions: [], nodes: [] };
const ONE: GraphDto = { questions: [{ root: 'q', text: 'Question?', claims: 1, active: true }], nodes: [] };

class FakeEventSource {
  static readonly CONNECTING = 0;
  static readonly OPEN = 1;
  static readonly CLOSED = 2;
  static instances: FakeEventSource[] = [];

  readonly url: string;
  readyState = FakeEventSource.CONNECTING;
  onopen: (() => void) | null = null;
  onmessage: ((event: MessageEvent<string>) => void) | null = null;
  onerror: (() => void) | null = null;
  closed = false;

  constructor(url: string) {
    this.url = url;
    FakeEventSource.instances.push(this);
  }

  close(): void {
    this.closed = true;
    this.readyState = FakeEventSource.CLOSED;
  }

  open(): void {
    this.readyState = FakeEventSource.OPEN;
    this.onopen?.();
  }

  message(graph: GraphDto): void {
    this.onmessage?.({ data: JSON.stringify(graph) } as MessageEvent<string>);
  }

  fail(state = FakeEventSource.CLOSED): void {
    this.readyState = state;
    this.onerror?.();
  }
}

const deferred = <T>() => {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((r) => { resolve = r; });
  return { promise, resolve };
};

beforeEach(() => {
  FakeEventSource.instances = [];
  vi.useFakeTimers();
  vi.stubGlobal('EventSource', FakeEventSource);
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('LiveSource', () => {
  it('loads /graph and opens the full-snapshot SSE endpoint', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(EMPTY), { status: 200 })));
    const graphs: GraphDto[] = [];
    const states: ConnState[] = [];

    new LiveSource().start((g) => graphs.push(g), (s) => states.push(s));
    await vi.runAllTimersAsync();

    expect(fetch).toHaveBeenCalledWith('/graph');
    expect(FakeEventSource.instances.map((e) => e.url)).toEqual(['/events']);
    expect(graphs).toEqual([EMPTY]);
    expect(states).toEqual(['connecting']);
  });

  it('publishes SSE snapshots live and lets a valid frame win the initial-fetch race', async () => {
    const initial = deferred<Response>();
    vi.stubGlobal('fetch', vi.fn().mockReturnValue(initial.promise));
    const graphs: GraphDto[] = [];
    const states: ConnState[] = [];
    const source = new LiveSource();

    source.start((g) => graphs.push(g), (s) => states.push(s));
    const es = FakeEventSource.instances[0];
    es.open();
    es.message(ONE);
    initial.resolve(new Response(JSON.stringify(EMPTY), { status: 200 }));
    await Promise.resolve();
    await Promise.resolve();

    expect(graphs).toEqual([ONE]);
    expect(states).toEqual(['connecting', 'live']);
  });

  it('ignores a malformed SSE frame without suppressing the initial graph', async () => {
    const initial = deferred<Response>();
    vi.stubGlobal('fetch', vi.fn().mockReturnValue(initial.promise));
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const graphs: GraphDto[] = [];

    new LiveSource().start((g) => graphs.push(g), () => undefined);
    FakeEventSource.instances[0].onmessage?.({ data: '{broken' } as MessageEvent<string>);
    initial.resolve(new Response(JSON.stringify(EMPTY), { status: 200 }));
    await vi.runAllTimersAsync();

    expect(graphs).toEqual([EMPTY]);
    expect(console.warn).toHaveBeenCalledOnce();
  });

  it('uses native reconnect while connecting and reopens a permanently closed stream', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(EMPTY), { status: 200 })));
    const states: ConnState[] = [];
    const source = new LiveSource();
    source.start(() => undefined, (s) => states.push(s));

    FakeEventSource.instances[0].fail(FakeEventSource.CONNECTING);
    await vi.advanceTimersByTimeAsync(1000);
    expect(FakeEventSource.instances).toHaveLength(1);

    FakeEventSource.instances[0].fail(FakeEventSource.CLOSED);
    await vi.advanceTimersByTimeAsync(999);
    expect(FakeEventSource.instances).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(FakeEventSource.instances).toHaveLength(2);
    expect(FakeEventSource.instances[0].closed).toBe(true);
    expect(states.at(-1)).toBe('connecting');
  });

  it('cancels reconnects and ignores stale fetches and events after stop or restart', async () => {
    const firstFetch = deferred<Response>();
    const secondFetch = deferred<Response>();
    vi.stubGlobal('fetch', vi.fn().mockReturnValueOnce(firstFetch.promise).mockReturnValueOnce(secondFetch.promise));
    const oldGraphs: GraphDto[] = [];
    const newGraphs: GraphDto[] = [];
    const source = new LiveSource();

    source.start((g) => oldGraphs.push(g), () => undefined);
    const oldEs = FakeEventSource.instances[0];
    oldEs.fail(FakeEventSource.CLOSED);
    source.start((g) => newGraphs.push(g), () => undefined);
    const newEs = FakeEventSource.instances[1];

    firstFetch.resolve(new Response(JSON.stringify(ONE), { status: 200 }));
    oldEs.message(ONE);
    secondFetch.resolve(new Response(JSON.stringify(EMPTY), { status: 200 }));
    await Promise.resolve();
    await Promise.resolve();
    await vi.advanceTimersByTimeAsync(1000);

    expect(oldEs.closed).toBe(true);
    expect(FakeEventSource.instances).toEqual([oldEs, newEs]);
    expect(oldGraphs).toEqual([]);
    expect(newGraphs).toEqual([EMPTY]);

    source.stop();
    newEs.message(ONE);
    expect(newGraphs).toEqual([EMPTY]);
    expect(newEs.closed).toBe(true);
  });
});
