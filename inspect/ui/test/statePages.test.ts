import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { CellState } from '../src/api/types';
import type { PageOutcome } from '../src/sync/detailClient';
import { DEFAULT_PAGE_LIMIT, StateWalk, mergePages } from '../src/sync/statePages';

function page(
  ref: string,
  entries: readonly string[],
  opts: {
    cursor?: string | null;
    walkStable?: boolean | null;
    exclusivesElided?: number;
    limit?: number;
    caveats?: readonly string[];
    attributes?: Record<string, unknown>;
  } = {},
): CellState {
  return {
    ref,
    frontier: null,
    kind: 'page',
    value: { $table: { columns: ['skill'], rows: entries.map((e) => [e]) } },
    staleMs: 0,
    provenance: 'live',
    page: {
      cursor: opts.cursor ?? null,
      limit: opts.limit ?? entries.length,
      entries: entries.length,
      exclusivesElided: opts.exclusivesElided ?? 0,
      // `?? true` would fold an explicit `null` (the shipped backend's
      // intermediate-page verdict) into `true` — the one value this helper
      // must be able to express verbatim (C10).
      walkStable: 'walkStable' in opts ? (opts.walkStable ?? null) : true,
      ...(opts.caveats ? { caveats: opts.caveats } : {}),
      ...(opts.attributes ? { attributes: opts.attributes as Record<string, never> } : {}),
    },
    unreadable: null,
  };
}

function view(ref: string): CellState {
  return { ref, frontier: { source: 'a', counter: 1 }, kind: 'view', value: 1, staleMs: 0 };
}

describe('mergePages', () => {
  it('a single page is returned as-is, merged', () => {
    const p = { $table: { columns: ['skill'], rows: [['Kotlin']] } };
    expect(mergePages([p])).toEqual({ value: p, merged: true });
  });

  it('concatenates $table pages that share columns', () => {
    const a = { $table: { columns: ['skill'], rows: [['Kotlin']] } };
    const b = { $table: { columns: ['skill'], rows: [['Rust']] } };
    expect(mergePages([a, b])).toEqual({
      value: { $table: { columns: ['skill'], rows: [['Kotlin'], ['Rust']] } },
      merged: true,
    });
  });

  it('concatenates plain-array pages', () => {
    expect(mergePages([[1, 2], [3]])).toEqual({ value: [1, 2, 3], merged: true });
  });

  it('does not fabricate a merge across mismatched shapes — renders pages in sequence', () => {
    const a = { $table: { columns: ['skill'], rows: [['Kotlin']] } };
    const b = { $table: { columns: ['level'], rows: [['9']] } };
    const result = mergePages([a, b]);
    expect(result.merged).toBe(false);
    expect(result.value).toEqual([a, b]);
  });

  it('an empty pages list is the empty array, trivially merged', () => {
    expect(mergePages([])).toEqual({ value: [], merged: true });
  });
});

describe('StateWalk', () => {
  let fetchStatePage: ReturnType<typeof vi.fn>;
  let onChange: ReturnType<typeof vi.fn>;
  let walk: StateWalk;

  beforeEach(() => {
    fetchStatePage = vi.fn();
    onChange = vi.fn();
    walk = new StateWalk({ fetchStatePage }, onChange);
  });

  it('seeding from a page-kind state populates the walk from page 1', () => {
    walk.seed('a', page('a', ['Kotlin', 'Rust'], { cursor: 'p-1', walkStable: true, exclusivesElided: 1 }));
    const s = walk.snapshot();
    expect(s.ref).toBe('a');
    expect(s.pagesFetched).toBe(1);
    expect(s.entriesTotal).toBe(2);
    expect(s.exclusivesElidedTotal).toBe(1);
    expect(s.walkStable).toBe(true);
    expect(s.cursor).toBe('p-1');
    expect(s.value).toEqual(page('a', ['Kotlin', 'Rust']).value);
  });

  it('seeding from a non-page kind (view/snapshot/unavailable) carries no walkable page', () => {
    walk.seed('a', view('a'));
    const s = walk.snapshot();
    expect(s.ref).toBe('a');
    expect(s.latest?.kind).toBe('view');
    expect(s.cursor).toBeNull();
    expect(s.pagesFetched).toBe(0);
  });

  it('loadNext is a no-op when the cursor is null (nothing to fetch)', async () => {
    walk.seed('a', page('a', ['Kotlin'], { cursor: null }));
    await walk.loadNext();
    expect(fetchStatePage).not.toHaveBeenCalled();
  });

  it('loadNext fetches exactly one more page and appends it, updating the running totals', async () => {
    walk.seed('a', page('a', ['Kotlin'], { cursor: 'p-1' }));
    const outcome: PageOutcome = { status: 'ok', state: page('a', ['Rust'], { cursor: null, walkStable: true }) };
    fetchStatePage.mockResolvedValueOnce(outcome);

    await walk.loadNext();

    expect(fetchStatePage).toHaveBeenCalledTimes(1);
    expect(fetchStatePage).toHaveBeenCalledWith('a', { cursor: 'p-1', limit: DEFAULT_PAGE_LIMIT });
    const s = walk.snapshot();
    expect(s.pagesFetched).toBe(2);
    expect(s.entriesTotal).toBe(2);
    expect(s.cursor).toBeNull();
    expect(s.value).toEqual({ $table: { columns: ['skill'], rows: [['Kotlin'], ['Rust']] } });
  });

  it('loadNext is a no-op while a fetch is already in flight', async () => {
    walk.seed('a', page('a', ['Kotlin'], { cursor: 'p-1' }));
    let resolvePending!: (o: PageOutcome) => void;
    fetchStatePage.mockImplementationOnce(() => new Promise<PageOutcome>((resolve) => (resolvePending = resolve)));

    const first = walk.loadNext();
    await walk.loadNext(); // synchronously a no-op — loading is already true
    expect(fetchStatePage).toHaveBeenCalledTimes(1);

    resolvePending({ status: 'ok', state: page('a', ['Rust'], { cursor: null }) });
    await first;
    expect(walk.snapshot().pagesFetched).toBe(2);
  });

  it('a stale cursor (410) restarts the walk from page 1, silently — no error, restarted: true', async () => {
    walk.seed('a', page('a', ['Kotlin'], { cursor: 'p-stale' }));
    fetchStatePage
      .mockResolvedValueOnce({ status: 'staleCursor' } satisfies PageOutcome)
      .mockResolvedValueOnce({ status: 'ok', state: page('a', ['Freshly-restarted'], { cursor: 'p-2' }) } satisfies PageOutcome);

    await walk.loadNext();

    expect(fetchStatePage).toHaveBeenCalledTimes(2);
    expect(fetchStatePage).toHaveBeenNthCalledWith(1, 'a', { cursor: 'p-stale', limit: DEFAULT_PAGE_LIMIT });
    expect(fetchStatePage).toHaveBeenNthCalledWith(2, 'a', { limit: DEFAULT_PAGE_LIMIT });
    const s = walk.snapshot();
    expect(s.restarted).toBe(true);
    expect(s.stuck).toBe(false);
    expect(s.pagesFetched).toBe(1); // the restart's own page 1, not a continuation
    expect(s.entriesTotal).toBe(1);
    expect(s.cursor).toBe('p-2');
  });

  it('a second consecutive stale cursor stops the walk rather than looping', async () => {
    walk.seed('a', page('a', ['Kotlin'], { cursor: 'p-stale' }));
    fetchStatePage.mockResolvedValue({ status: 'staleCursor' } satisfies PageOutcome);

    await walk.loadNext();

    expect(fetchStatePage).toHaveBeenCalledTimes(2); // one attempt, one restart attempt — no third
    const s = walk.snapshot();
    expect(s.stuck).toBe(true);
    expect(s.loading).toBe(false);
  });

  it('abandon clears the accumulator and discards an in-flight response when it lands', async () => {
    walk.seed('a', page('a', ['Kotlin'], { cursor: 'p-1' }));
    let resolvePending!: (o: PageOutcome) => void;
    fetchStatePage.mockImplementationOnce(() => new Promise<PageOutcome>((resolve) => (resolvePending = resolve)));

    const inFlight = walk.loadNext();
    walk.abandon();
    expect(walk.snapshot().ref).toBeNull();

    resolvePending({ status: 'ok', state: page('a', ['Rust'], { cursor: null }) });
    await inFlight;

    // the abandoned response must not resurrect the walk
    expect(walk.snapshot().ref).toBeNull();
    expect(walk.snapshot().pagesFetched).toBe(0);
  });

  it('reseeding (a fresh page 1) always starts a new walk, discarding any older in-flight loadNext', async () => {
    walk.seed('a', page('a', ['Kotlin'], { cursor: 'p-1' }));
    let resolvePending!: (o: PageOutcome) => void;
    fetchStatePage.mockImplementationOnce(() => new Promise<PageOutcome>((resolve) => (resolvePending = resolve)));

    const inFlight = walk.loadNext();
    walk.seed('a', page('a', ['Fresh'], { cursor: 'p-new' })); // e.g. a summary-triggered refetch landed first

    resolvePending({ status: 'ok', state: page('a', ['Stale-page-2'], { cursor: null }) });
    await inFlight;

    const s = walk.snapshot();
    expect(s.pagesFetched).toBe(1);
    expect(s.cursor).toBe('p-new');
    expect(s.value).toEqual(page('a', ['Fresh']).value);
  });

  it('notifies onChange on every state transition', () => {
    walk.seed('a', page('a', ['Kotlin'], { cursor: 'p-1' }));
    expect(onChange).toHaveBeenCalledWith(walk.snapshot());
  });

  /** C10 — asserted against the SHIPPED backend rather than the draft
   *  contract block this ticket was written against: `walkStable` is `true`
   *  on page 1, **`null` on every intermediate page** (the page carries only
   *  the walk's opening frontier, so no verdict exists yet) and a real
   *  verdict only when the walk closes. `InspectorPagedStateTest`'s "a walk
   *  over a quiescent fold closes stable" pins exactly that sequence. */
  it('carries an intermediate page’s null walkStable as null — not as a false', async () => {
    walk.seed('a', page('a', ['Kotlin', 'Rust'], { cursor: 'p-1', walkStable: true }));
    fetchStatePage.mockResolvedValueOnce({
      status: 'ok',
      state: page('a', ['Go', 'Scala'], { cursor: 'p-2', walkStable: null, caveats: ['staleFrontier'] }),
    });
    await walk.loadNext();
    expect(walk.snapshot().walkStable).toBeNull();

    fetchStatePage.mockResolvedValueOnce({
      status: 'ok',
      state: page('a', ['Zig'], { cursor: null, walkStable: true }),
    });
    await walk.loadNext();
    expect(walk.snapshot().walkStable).toBe(true);
  });

  /** C10 — `page.caveats`/`page.attributes` are shipped fields the draft
   *  omitted entirely. Caveats union across the walk (a weakening declared
   *  once holds for the union of pages it covered); attributes are the
   *  latest page's, since they are a per-read reading of cell-level state. */
  it('unions caveats across the walk and keeps the latest page’s attributes', async () => {
    walk.seed(
      'a',
      page('a', ['Kotlin'], { cursor: 'p-1', caveats: ['positionalCursor'], attributes: { counter: 7 } }),
    );
    expect(walk.snapshot().caveats).toEqual(['positionalCursor']);
    expect(walk.snapshot().attributes).toEqual({ counter: 7 });

    fetchStatePage.mockResolvedValueOnce({
      status: 'ok',
      state: page('a', ['Rust'], {
        cursor: null,
        caveats: ['staleFrontier', 'positionalCursor'],
        attributes: { counter: 9 },
      }),
    });
    await walk.loadNext();

    expect(walk.snapshot().caveats).toEqual(['positionalCursor', 'staleFrontier']);
    expect(walk.snapshot().attributes).toEqual({ counter: 9 });
  });

  it('an older server that omits caveats/attributes entirely walks unchanged', async () => {
    walk.seed('a', page('a', ['Kotlin'], { cursor: 'p-1' }));
    expect(walk.snapshot().caveats).toEqual([]);
    expect(walk.snapshot().attributes).toEqual({});

    fetchStatePage.mockResolvedValueOnce({ status: 'ok', state: page('a', ['Rust'], { cursor: null }) });
    await walk.loadNext();

    expect(walk.snapshot().caveats).toEqual([]);
    expect(walk.snapshot().attributes).toEqual({});
  });
});
