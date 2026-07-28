# M5-SEARCH — Content search: find the cell holding a record (full vertical)

**Status**: Specified — not yet dispatched (see `00-orchestration.md` §Ticket index).

Model: `claude-opus-5` (effort xhigh) · Track: full vertical (BE + FE) ·
Depends: M4-EVAL merged · Parallel with: M5-NET (disjoint files) · M5-COLD
runs AFTER this merges (both touch navigator search UI)

Files owned: `inspect/src/**` (search), `inspect/ui/**` (search UI data mode).

## Context

"Which graph holds this record?" — search by data content. This is the most
kernel-sensitive search mode: a naive implementation would subscribe to or
wake everything (P6 violation). The decided cost model
(`10-target-v3.md` §Known-gaps, Linear MRB-157): query **pull-serving cells
only** (cells advertising the `PULL_SERVICE` nature), **hot cones only**, and
**surface the cost in the response**. Seams: `StateRequestProtocol`
(`kernel/.../cell/protocol/StateRequestProtocol.kt`) — a management-class
pull: `StateRequest(replyTo, since, scope)` answered by pull-serving
producers with one state-as-delta reply; the M1 `ValueEncoder` for matching
against encoded state.

Alternative it is acceptable to prefer after investigation: for cells the
inspector can already read cheaply (active observations, or `Stateful`
snapshot via host routing), match against those reads instead of the
StateRequest protocol — choose the design that keeps P6 intact and document
the choice. What is NOT acceptable: creating observations (attention!) for
cells solely to search them, or waking suspended cones.

## Implement

1. **BE `GET /search?mode=data&q=`** per contract: enumerate candidate cells
   (pull-serving or cheaply-readable, hot only); fetch/encode their state
   bounded (reuse ValueEncoder limits); substring/equality match `q` against
   encoded scalar values; return hits `{graph, ref, label, detail}` (label =
   the matching value, detail = "graph / cell · type — n record(s)") and the
   `cost` object (`cellsQueried`, `coldSkipped` — cold is 0 until M5-COLD
   merges; leave the field wired). Bound the whole search: max 50 cells
   queried, 2 s budget, partial results flagged in `detail` of a final
   pseudo-hit or an added `"partial": true` — if you need the field, request
   a contract addition via the orchestrator.
2. **FE**: enable the data chip: as-you-submit (Enter, not per-keystroke)
   search; render hits (click → open graph, select cell, State subsection
   visible); render the cost line under the results ("queried N cells ·
   M cold skipped") — the cost being visible is a product requirement, not
   decoration.
3. Keep name/problems modes untouched.

## Exclusions

Indexing, persistence, regex/query languages. Waking cold/suspended anything.
Cross-JVM search (remote refs are skipped; count them in a `detail` note if
cheap). No per-keystroke querying.

## Tests / acceptance

- BE: seeded graph with known records — hit found with correct ref; cell
  budget respected (build >50 candidates, assert cap + partial signal);
  suspended cell skipped (assert no attention raised — reuse the M1-EVAL
  leak-check technique); 2 s budget respected with a deliberately slow cell
  if constructible, else document why untestable.
- FE: submit → hits → click-through; cost line rendering.
- `./gradlew :inspect:test`, `npm test` green; curl + screenshot transcript
  in the report.
