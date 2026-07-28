import { createSignal } from 'solid-js';
import type { SearchCost, SearchHit, SearchMode } from '../api/types';
import { isSearchModeEnabled } from '../nav/search';
import { defaultSearchTransport, type SearchTransport } from '../sync/searchClient';

/** M4-FE ticket Implement §4: "input + mode chips (name / problems / data)."
 *  A plain module-level signal set, same shape as `solid/graphs.ts`/
 *  `solid/errors.ts` — `Navigator.tsx` is the only reader. */
const [searchMode, setSearchMode] = createSignal<SearchMode>('name');
const [searchQuery, setSearchQuery] = createSignal('');
const [searchHits, setSearchHits] = createSignal<readonly SearchHit[]>([]);
const [searchLoading, setSearchLoading] = createSignal(false);
const [searchError, setSearchError] = createSignal<unknown>(null);
/** Data mode's `cost` (M5) — null for name/problems, which the server sends
 *  `cost: null` for. Held as its own signal because the cost line has to
 *  survive a result with zero hits: "queried 12 cells, found nothing" is the
 *  answer, not an empty panel. */
const [searchCost, setSearchCost] = createSignal<SearchCost | null>(null);
export {
  searchCost,
  searchError,
  searchHits,
  searchLoading,
  searchMode,
  searchQuery,
  setSearchMode,
  setSearchQuery,
};

let transport: SearchTransport = defaultSearchTransport;

/** Test seam: swap the transport before calling {@link runSearch}. */
export function setSearchTransport(t: SearchTransport): void {
  transport = t;
}

let epoch = 0;

/** `GET /api/inspect/search?mode=...&q=...`. `Navigator.tsx` calls this
 *  as-you-type for `name`, once on chip-select for `problems`, and on submit
 *  (Enter) for `data` — never per keystroke, because every data query reads
 *  real cell state (10-target-v3.md Navigator; M4-FE ticket Implement §4;
 *  M5-SEARCH ticket Implement §2 and Exclusions). An `epoch` counter guards a
 *  stale response from a fast mode/query switch landing after a newer one
 *  already resolved — same technique as `sync/detailClient.ts`'s
 *  `DetailController`. */
export function runSearch(mode: SearchMode, q: string): void {
  const my = ++epoch;
  if (!isSearchModeEnabled(mode)) {
    clearSearch();
    return;
  }
  setSearchLoading(true);
  void transport.search(mode, q).then(
    (result) => {
      if (my !== epoch) return;
      setSearchLoading(false);
      setSearchHits(result.hits);
      setSearchCost(result.cost ?? null);
      setSearchError(null);
    },
    (err) => {
      if (my !== epoch) return;
      setSearchLoading(false);
      setSearchError(err);
      console.error('inspect: search failed', err);
    },
  );
}

/** Drop the current result set — a mode switch, or an emptied `name` box.
 *  Bumps the epoch so an in-flight response cannot repopulate what the user
 *  just cleared. */
export function clearSearch(): void {
  epoch += 1;
  setSearchHits([]);
  setSearchCost(null);
  setSearchError(null);
  setSearchLoading(false);
}
