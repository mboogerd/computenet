import type { GraphSummary, SearchCost } from '../api/types';

/** The v2 mockup's cold marker. One place, so a card, a thumbnail and the
 *  cold screen all wear the same tag. */
export const COLD_TAG = '❄';

/** What the cold-screen banner says about a parked graph, now that `V1C-BE`
 *  closed the "cold reads from a checkpoint or journal" kernel gap
 *  (`V1C-KERNEL` Decision 7, Linear MRB-157) this constant used to cite as
 *  the reason state/flow were BOTH unavailable while cold. That reason is
 *  gone for state — a selection inside a cold graph now issues one plain
 *  `GET .../state` (never a `POST observe`) and renders it labelled with its
 *  provenance, exactly like a hot cell's read (`util/statePresentation.ts`'s
 *  `provenanceLabel`). It is NOT gone for flow: no messages flow in a parked
 *  cone, and there is nothing honest to show — see {@link COLD_FLOW_NOTICE}.
 *
 *  The no-fake-preview discipline (M5-COLD ticket Exclusions — "the cold
 *  screen says 'unavailable', not a fake preview") still holds in its
 *  original form: this notice must never claim the value it goes on to show
 *  is CURRENT. A truthful "checkpoint"/"suspended" label is not a stale
 *  preview dressed up as live — it says exactly what it is, which is the
 *  opposite of the dishonesty the exclusion forbids. `test/cold.test.ts`
 *  asserts this distinction survives, not the literal old sentence. */
export const COLD_BANNER_NOTICE =
  'cold — parked. Selecting a cell reads its state, labelled with where it came from (its own parked fold, or a drain checkpoint); flow does not run in a parked cone.';

/** The `State` subsection's cold-specific line — shown ABOVE the real,
 *  fetched value (unlike the old fallback, which replaced the whole
 *  section). See {@link COLD_BANNER_NOTICE}'s doc comment for why this no
 *  longer says "unavailable": V1C-BE makes a parked cell's state readable,
 *  and this line exists to say that plainly, not to hide it.
 *
 *  C10 wording correction: it says reading does not wake, not that a value
 *  WAS read — the line renders above the State subsection whatever landed
 *  there, and a parked cell can still answer `unavailable` (held for a
 *  migration flip, not `Stateful`, a dead host). "Its state below was read"
 *  over "State unavailable for this cell." would be the panel contradicting
 *  itself two lines apart. */
export const COLD_STATE_NOTICE = 'cold — this cell is parked. Reading its state here does not wake it.';

/** The `Flow` subsection's cold-specific line — flow is the half of the old
 *  combined notice that is still true without qualification: nothing flows
 *  through a parked cone, so there is nothing honest to render in the
 *  per-port rate table (V1C-FE ticket Solution direction §3: "`FlowSection`
 *  has no cold gate at all today — while cold it renders a port table of
 *  em-dashes", which read as "no traffic" rather than "cannot be measured"). */
export const COLD_FLOW_NOTICE = 'cold — no messages flow in a parked cone. Nothing to show.';

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

/** The inline hint under a data-search result when the search skipped some
 *  cells (M5-COLD ticket Implement §3). Null when nothing was skipped, so the
 *  hint only ever appears when there is something to act on.
 *
 *  Wording note: `SearchCost.coldSkipped` counts *cells*, not graphs (see
 *  20-api-contract.md §SearchResult and the server's `DataSearch`), so this
 *  says cells and points at the graphs — claiming "N cold graphs skipped" from
 *  a cell count would be a number the UI made up.
 *
 *  V1C-BE/V1C-FE correction — **why this no longer says "wake to include"**:
 *  `V1C-BE` narrows `coldSkipped`'s meaning to "held for a migration flip
 *  only" (it also makes suspended/drained cells directly searchable, so they
 *  stop being counted here at all). Waking does nothing for a held cell
 *  (`Cold.kt`'s "never the inspector" — the migration's own release ends the
 *  hold, not a wake button). But a browser cannot tell whether it is talking
 *  to a pre- or post-`V1C-BE` server, and on an OLDER server `coldSkipped`
 *  still counts parked (suspended/drained) cells too, for which waking WOULD
 *  help. So this hint is worded to be true under both readings: it states the
 *  fact (cells were skipped, for a parked-or-held reason) and drops the
 *  remedy claim rather than naming one ("wake to include") that is a dead end
 *  under the narrowed, post-merge meaning.
 *
 *  C10 correction: it must not name the *category* either. The shipped
 *  `DataSearch` counts held-for-migration cells ONLY and searches suspended
 *  and drained cells normally, so "parked ... cells are not searched" — the
 *  first attempt at this wording — is false against the merged backend, in
 *  the same direction the old "wake their graph" claim was. What is true
 *  under both server generations is the fact the count itself carries:
 *  these cells' state could not be read, so they are missing from the
 *  result. That, and nothing more. */
export function formatColdSkipHint(cost: SearchCost | null): string | null {
  if (!cost || cost.coldSkipped <= 0) return null;
  const cells = `${cost.coldSkipped} cold ${cost.coldSkipped === 1 ? 'cell' : 'cells'}`;
  return `${cells} skipped — their state could not be read for this search`;
}
