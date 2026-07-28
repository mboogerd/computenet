import type { SearchMode, SearchResult } from '../api/types';

/** `GET /api/inspect/search?mode={name|problems|data}&q=` (20-api-contract.md
 *  "Endpoints"). Same shape as `GraphsTransport`/`ErrorsTransport`: a plain
 *  fetch behind an interface for `solid/search.ts`'s unit-testability. */
export interface SearchTransport {
  search(mode: SearchMode, q: string): Promise<SearchResult>;
}

export const defaultSearchTransport: SearchTransport = {
  search: async (mode, q) => {
    const res = await fetch(`/api/inspect/search?mode=${encodeURIComponent(mode)}&q=${encodeURIComponent(q)}`);
    if (!res.ok) throw new Error(`HTTP ${res.status} for ${res.url}`);
    return (await res.json()) as SearchResult;
  },
};
