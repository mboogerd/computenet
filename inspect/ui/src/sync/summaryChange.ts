import type { Frontier, StateSummaryPayload } from '../api/types';

/** V1A-FE ticket Implement §1. The single definition of "this `state.summary`
 *  represents an effective change" — shared by `DetailController`'s refetch
 *  gate (`detailClient.ts`) and `changeLog.ts`'s append gate, so there is
 *  exactly one place that decides "changed vs quiet", not two independently
 *  drifting ones.
 *
 *  `staleMs` is the load-bearing clause: V1A-BE's coalesced `state.summary`
 *  computes it at publish time from the last *effective* change, so across a
 *  run of published windows it decreases exactly when a change settled in
 *  that window and otherwise grows monotonically (quiet window after quiet
 *  window). A publish-even-when-quiet feed with a `staleMs` that never
 *  reflected that shape would make this predicate unable to tell "still
 *  quiet" from "just changed" — see mock/serve.mjs's tick, which is written
 *  to exercise exactly this. */
export function indicatesChange(prev: StateSummaryPayload | undefined, next: StateSummaryPayload): boolean {
  if (prev === undefined) return true; // first summary seen for this ref since selection
  if (!sameFrontier(prev.frontier, next.frontier)) return true;
  if (next.cardinality !== prev.cardinality) return true;
  if (next.staleMs < prev.staleMs) return true;
  return false;
}

function sameFrontier(a: Frontier | null, b: Frontier | null): boolean {
  if (a === null || b === null) return a === b; // a null/non-null transition counts as different
  return a.source === b.source && a.counter === b.counter;
}
