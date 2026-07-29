import { createRoot } from 'solid-js';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { CellDetail, CellState, Value } from '../src/api/types';

/** Regression test for a bug found live during V1B-FE evaluation:
 *  `DetailController.loadState` was widened (V1B-FE Solution direction §1)
 *  so a pinned-but-not-selected ref's `GET /state` response now delivers via
 *  `onState` too (correct — a pin needs its own state fetched). But
 *  `solid/detail.ts`'s `onState` handler ignored the `ref` argument and
 *  wrote every response into the single `cellState` signal that backs the
 *  detail panel — so a pinned cell's response could stomp the actually-
 *  selected cell's displayed state. Confirmed live: with `jobSkills`
 *  selected and `matches` pinned, the panel titled "jobSkills" showed
 *  `matches`' data.
 *
 *  `solid/detail.ts` has no existing test file (it is a module-level
 *  singleton wired directly to the real `fetch`-based transport, unlike
 *  `sync/detailClient.ts`'s framework-free, constructor-injected
 *  `DetailController` that `test/detailClient.test.ts` exercises). This file
 *  is a minimal harness for it: `vi.resetModules()` + a fresh dynamic
 *  `import()` per test gives each test its own copy of the singleton
 *  signals/controller, and a `fetch` mock keyed by `"METHOD url"` stands in
 *  for the network. `globalThis.location` is stubbed because
 *  `solid/routeState.ts` (an indirect import of `solid/detail.ts` via
 *  `solid/cold.ts`) reads `location.hash` at module load time, and this
 *  suite's `environment: 'node'` vitest config has no `location` global. */

function cellDetail(ref: string): CellDetail {
  return {
    ref,
    name: ref,
    typeFqn: 'civictech.cell.data.SetCell',
    color: 'PURE',
    manifests: [],
    ports: [],
    host: 'h1',
    net: 'local',
    lifecycle: 'HOT',
    generation: 0,
    graph: null,
    attention: null,
    links: { inbound: 0, outbound: 0, taps: 0 },
  };
}

function cellState(ref: string, value: Value): CellState {
  return { ref, frontier: null, kind: 'view', value, staleMs: 0 };
}

interface MockResponse {
  ok: boolean;
  status: number;
  url: string;
  json: () => Promise<unknown>;
}

function okJson(url: string, body: unknown, status = 200): MockResponse {
  return { ok: status < 300, status, url, json: async () => body };
}

function deferredResponse(url: string): { promise: Promise<MockResponse>; resolve: (body: unknown, status?: number) => void } {
  let resolve!: (body: unknown, status?: number) => void;
  const promise = new Promise<MockResponse>((res) => {
    resolve = (body: unknown, status = 200) => res(okJson(url, body, status));
  });
  return { promise, resolve };
}

/** Flush enough microtask ticks for the chained `.then()`s in
 *  `DetailController` AND the Solid effect scheduler (an extra layer beyond
 *  a plain promise chain) to settle. */
async function flush(): Promise<void> {
  for (let i = 0; i < 20; i++) await Promise.resolve();
  await new Promise((resolve) => setTimeout(resolve, 0));
  for (let i = 0; i < 20; i++) await Promise.resolve();
}

describe('solid/detail bridge — onState ref guard (V1B-FE regression)', () => {
  let fetchMock: ReturnType<typeof vi.fn>;
  let routes: Map<string, () => Promise<MockResponse>>;
  let dispose: (() => void) | undefined;

  beforeEach(() => {
    vi.resetModules();
    (globalThis as unknown as { location: { hash: string } }).location = { hash: '' };
    routes = new Map();
    fetchMock = vi.fn((url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET';
      const key = `${method} ${url}`;
      const provider = routes.get(key);
      if (!provider) return Promise.reject(new Error(`no mock response for ${key}`));
      return provider();
    });
    (globalThis as unknown as { fetch: unknown }).fetch = fetchMock;
  });

  afterEach(() => {
    dispose?.();
    dispose = undefined;
  });

  function mockImmediate(key: string, body: unknown, status = 200): void {
    const url = key.split(' ').slice(1).join(' ');
    routes.set(key, async () => okJson(url, body, status));
  }

  function mockDeferred(key: string): ReturnType<typeof deferredResponse> {
    const url = key.split(' ').slice(1).join(' ');
    const d = deferredResponse(url);
    routes.set(key, () => d.promise);
    return d;
  }

  it("a pinned ref's state response never overwrites the selected ref's cellState, even when it resolves later", async () => {
    const { setSelection } = await import('../src/solid/selection');
    const detail = await import('../src/solid/detail');

    mockImmediate('GET /api/inspect/cell/jobSkills', cellDetail('jobSkills'));
    mockImmediate('POST /api/inspect/cell/jobSkills/observe', undefined, 204);
    mockImmediate('GET /api/inspect/cell/jobSkills/state', cellState('jobSkills', 'jobSkills-value'));
    mockImmediate('POST /api/inspect/cell/matches/observe', undefined, 204);
    const matchesState = mockDeferred('GET /api/inspect/cell/matches/state');

    createRoot((d) => {
      dispose = d;
      detail.initDetail();
    });

    setSelection('jobSkills');
    await flush();
    expect(detail.cellState()?.ref).toBe('jobSkills');
    expect(detail.cellState()?.value).toBe('jobSkills-value');

    // Pin a second, different cell — its own observe+state fetch is issued,
    // but its state has not resolved yet.
    detail.pin('matches');
    await flush();
    // Still showing the selected cell's own value — pinning alone must not
    // touch the panel.
    expect(detail.cellState()?.ref).toBe('jobSkills');

    // Now the pinned cell's (slower) state response lands.
    matchesState.resolve(cellState('matches', 'matches-value'));
    await flush();

    // BUG (pre-fix): this would become 'matches' / 'matches-value', stomping
    // the selected cell's displayed state with the pinned cell's data.
    expect(detail.cellState()?.ref).toBe('jobSkills');
    expect(detail.cellState()?.value).toBe('jobSkills-value');
  });

  it('selecting a different ref after a pin still updates cellState to the newly selected ref (guard does not break the normal path)', async () => {
    const { setSelection } = await import('../src/solid/selection');
    const detail = await import('../src/solid/detail');

    mockImmediate('GET /api/inspect/cell/jobSkills', cellDetail('jobSkills'));
    mockImmediate('POST /api/inspect/cell/jobSkills/observe', undefined, 204);
    mockImmediate('GET /api/inspect/cell/jobSkills/state', cellState('jobSkills', 'jobSkills-value'));
    mockImmediate('POST /api/inspect/cell/matches/observe', undefined, 204);
    mockImmediate('GET /api/inspect/cell/matches/state', cellState('matches', 'matches-value'));
    mockImmediate('GET /api/inspect/cell/matches', cellDetail('matches'));
    // jobSkills is not pinned, so selecting 'matches' next releases it.
    mockImmediate('DELETE /api/inspect/cell/jobSkills/observe', undefined, 204);

    createRoot((d) => {
      dispose = d;
      detail.initDetail();
    });

    setSelection('jobSkills');
    await flush();
    detail.pin('matches'); // matches pinned but not selected — no-op on cellState
    await flush();
    expect(detail.cellState()?.ref).toBe('jobSkills');

    setSelection('matches'); // now actually select the pinned cell
    await flush();
    expect(detail.cellState()?.ref).toBe('matches');
    expect(detail.cellState()?.value).toBe('matches-value');
  });
});
