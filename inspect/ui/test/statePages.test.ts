import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { CellState } from '../src/api/types';
import type { PageOutcome } from '../src/sync/detailClient';
import { DEFAULT_PAGE_LIMIT, StateWalk, mergePages } from '../src/sync/statePages';

function page(
  ref: string,
  entries: readonly string[],
  opts: { cursor?: string | null; walkStable?: boolean | null; exclusivesElided?: number; limit?: number } = {},
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
      walkStable: opts.walkStable ?? true,
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
});
