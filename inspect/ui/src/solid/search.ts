import { createSignal } from 'solid-js';
import type { SearchHit, SearchMode } from '../api/types';
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
export { searchError, searchHits, searchLoading, searchMode, searchQuery, setSearchMode, setSearchQuery };

let transport: SearchTransport = defaultSearchTransport;

/** Test seam: swap the transport before calling {@link runSearch}. */
export function setSearchTransport(t: SearchTransport): void {
  transport = t;
}

let epoch = 0;

/** `GET /api/inspect/search?mode=...&q=...`. `Navigator.tsx` calls this
 *  as-you-type for `name` and once on chip-select for `problems`
 *  (10-target-v3.md Navigator; ticket Implement §4) — `data` is disabled at
 *  the chip (`nav/search.ts`), so this never issues a request for it, ahead
 *  of the BE's own 501 (M4-BE ticket Implement §4). An `epoch` counter
 *  guards a stale response from a fast mode/query switch landing after a
 *  newer one already resolved — same technique as
 *  `sync/detailClient.ts`'s `DetailController`. */
export function runSearch(mode: SearchMode, q: string): void {
  const my = ++epoch;
  if (!isSearchModeEnabled(mode)) {
    setSearchHits([]);
    return;
  }
  setSearchLoading(true);
  void transport.search(mode, q).then(
    (result) => {
      if (my !== epoch) return;
      setSearchLoading(false);
      setSearchHits(result.hits);
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
