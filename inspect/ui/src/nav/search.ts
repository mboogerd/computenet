import type { SearchCost, SearchHit, SearchMode } from '../api/types';

export interface SearchModeOption {
  readonly mode: SearchMode;
  readonly label: string;
  readonly enabled: boolean;
  /** Shown as the disabled chip's tooltip (M4-FE ticket Implement §4:
   *  "Data: chip disabled with tooltip 'arrives in M5'"). Absent for an
   *  enabled mode. */
  readonly disabledReason?: string;
  /** True when the mode runs on submit (Enter) rather than as-you-type.
   *  `data` is the only one: every data query reads real cell state on the
   *  cells' own host threads (server `DataSearch`), so per-keystroke
   *  querying is explicitly excluded by the M5-SEARCH ticket. */
  readonly onSubmit?: boolean;
}

/** 10-target-v3.md Navigator: "Search with modes: name (live filter),
 *  problems (...), data (M5 — find the cell holding a record)"; M4-FE
 *  ticket Implement §4, M5-SEARCH ticket Implement §2 (which enables
 *  `data`). */
export const SEARCH_MODES: readonly SearchModeOption[] = [
  { mode: 'name', label: 'Name', enabled: true },
  { mode: 'problems', label: 'Problems', enabled: true },
  { mode: 'data', label: 'Data', enabled: true, onSubmit: true },
];

export function isSearchModeEnabled(mode: SearchMode): boolean {
  return SEARCH_MODES.some((m) => m.mode === mode && m.enabled);
}

/** True when {@link mode} must not query on every keystroke — see
 *  {@link SearchModeOption.onSubmit}. */
export function isSubmitMode(mode: SearchMode): boolean {
  return SEARCH_MODES.some((m) => m.mode === mode && m.onSubmit === true);
}

/** The server's marker for the closing notice row (`DataSearch.NOTICE_GRAPH`):
 *  a hit whose `graph` is empty is not navigable — it reports what the search
 *  did *not* cover ("stopped at the 50-cell cap", "3 remote cells skipped").
 *  No real component id can be empty (they are `g-<uuid>`), so the two can
 *  never be confused. The M5-SEARCH ticket sanctions this pseudo-hit
 *  explicitly, as the alternative to adding a `partial` field that
 *  `20-api-contract.md` does not have. */
export function isNoticeHit(hit: SearchHit): boolean {
  return hit.graph === '';
}

/** The cost line under a data-mode result — M5-SEARCH ticket Implement §2:
 *  "render the cost line under the results ('queried N cells · M cold
 *  skipped') — the cost being visible is a product requirement, not
 *  decoration". Null for the modes that have no cost (name/problems answer
 *  from metadata the server already holds and send `cost: null`). */
export function formatSearchCost(cost: SearchCost | null): string | null {
  if (!cost) return null;
  const queried = `queried ${cost.cellsQueried} ${cost.cellsQueried === 1 ? 'cell' : 'cells'}`;
  return `${queried} · ${cost.coldSkipped} cold skipped`;
}
