import type { GraphSummary, SearchCost } from '../api/types';

/** The v2 mockup's cold marker. One place, so a card, a thumbnail and the
 *  cold screen all wear the same tag. */
export const COLD_TAG = '❄';

/** What the cold screen says instead of state and flow. It says *unavailable*,
 *  never a stale preview: reading a parked cone's last known value and
 *  presenting it as its state would be the one dishonesty this screen exists
 *  to avoid (M5-COLD ticket Exclusions — "the cold screen says 'unavailable',
 *  not a fake preview"; the real capability, cold reads from a checkpoint or
 *  journal, is a tracked kernel gap, Linear MRB-157). */
export const COLD_NOTICE = 'cold — parked; state/flow unavailable without waking';

/** The confirmation the wake button asks for, verbatim from the ticket. Waking
 *  is the only act in the whole inspector that changes the graph it inspects
 *  (10-target-v3.md §Constraints 2 — observation is causal), so it is never
 *  implicit and never a side effect of browsing. */
export const WAKE_CONFIRMATION = 'Waking raises attention and resumes execution.';

/** True when `graphId` is a component the loaded list reports as cold.
 *  Deliberately false for an unknown id and for a null one: "no list yet" and
 *  "not in the list" both mean *this client does not know*, and guessing cold
 *  would ghost a live graph — the same conservative default `graphIsGone`
 *  takes for the same reason. */
export function isGraphCold(graphId: string | null, graphs: readonly GraphSummary[]): boolean {
  if (!graphId) return false;
  return graphs.some((g) => g.id === graphId && g.lifecycle === 'cold');
}

/** The number of cold graphs in the list — what the navigator's search hint
 *  offers to include. */
export function coldGraphCount(graphs: readonly GraphSummary[]): number {
  return graphs.filter((g) => g.lifecycle === 'cold').length;
}

/** The inline hint under a data-search result when the search skipped parked
 *  cells (M5-COLD ticket Implement §3). Null when nothing was skipped, so the
 *  hint only ever appears when there is something to act on.
 *
 *  Wording note: `SearchCost.coldSkipped` counts *cells*, not graphs (see
 *  20-api-contract.md §SearchResult and the server's `DataSearch`), so this
 *  says cells and points at the graphs — claiming "N cold graphs skipped" from
 *  a cell count would be a number the UI made up. */
export function formatColdSkipHint(cost: SearchCost | null): string | null {
  if (!cost || cost.coldSkipped <= 0) return null;
  const cells = `${cost.coldSkipped} cold ${cost.coldSkipped === 1 ? 'cell' : 'cells'}`;
  return `${cells} skipped — wake their graph to include`;
}
