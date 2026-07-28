import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { SearchMode, SearchResult } from '../src/api/types';
import {
  clearSearch,
  runSearch,
  searchCost,
  searchError,
  searchHits,
  searchLoading,
  setSearchTransport,
} from '../src/solid/search';

const dataResult: SearchResult = {
  mode: 'data',
  hits: [
    {
      graph: 'g-016eda8f-96de-40e1-a04c-f997395ade62',
      ref: 'e7651ef0-8140-48c3-b9a9-647bac311c4c:0',
      label: 'alice',
      detail: 'skillmatch / candSkills · SetCell — 2 records',
    },
    { graph: '', ref: null, label: 'Partial results', detail: 'stopped at the 50-cell cap' },
  ],
  cost: { cellsQueried: 50, coldSkipped: 0 },
};

const nameResult: SearchResult = {
  mode: 'name',
  hits: [{ graph: 'g-016eda8f-96de-40e1-a04c-f997395ade62', ref: null, label: 'skillmatch', detail: '16 cells' }],
  cost: null,
};

/** M5-SEARCH ticket Implement §2 — the store half: a data result carries a
 *  cost the panel renders, and it has to survive a zero-hit answer (the whole
 *  point of showing what a search cost). */
describe('solid/search — data mode', () => {
  beforeEach(() => {
    clearSearch();
  });

  function transportReturning(result: SearchResult, seen?: { mode?: SearchMode; q?: string }) {
    setSearchTransport({
      search: async (mode, q) => {
        if (seen) {
          seen.mode = mode;
          seen.q = q;
        }
        return result;
      },
    });
  }

  it('stores the hits and the cost of a data search', async () => {
    const seen: { mode?: SearchMode; q?: string } = {};
    transportReturning(dataResult, seen);

    runSearch('data', 'alice');
    await vi.waitFor(() => expect(searchLoading()).toBe(false));

    expect(seen.mode).toBe('data');
    expect(seen.q).toBe('alice');
    expect(searchHits()).toHaveLength(2);
    expect(searchCost()).toEqual({ cellsQueried: 50, coldSkipped: 0 });
    expect(searchError()).toBeNull();
  });

  it('keeps the cost of a search that found nothing', async () => {
    transportReturning({ mode: 'data', hits: [], cost: { cellsQueried: 12, coldSkipped: 3 } });

    runSearch('data', 'nothing');
    await vi.waitFor(() => expect(searchLoading()).toBe(false));

    expect(searchHits()).toHaveLength(0);
    expect(searchCost()).toEqual({ cellsQueried: 12, coldSkipped: 3 });
  });

  it('clears the cost when a name search follows (contract: cost is data-mode only)', async () => {
    transportReturning(dataResult);
    runSearch('data', 'alice');
    await vi.waitFor(() => expect(searchCost()).not.toBeNull());

    transportReturning(nameResult);
    runSearch('name', 'skill');
    await vi.waitFor(() => expect(searchLoading()).toBe(false));

    expect(searchCost()).toBeNull();
    expect(searchHits()).toHaveLength(1);
  });

  it('clearSearch drops hits and cost, and an in-flight response cannot restore them', async () => {
    let release: (r: SearchResult) => void = () => {};
    setSearchTransport({ search: () => new Promise<SearchResult>((resolve) => (release = resolve)) });

    runSearch('data', 'alice');
    clearSearch();
    release(dataResult);
    await Promise.resolve();
    await Promise.resolve();

    expect(searchHits()).toHaveLength(0);
    expect(searchCost()).toBeNull();
    expect(searchLoading()).toBe(false);
  });
});
