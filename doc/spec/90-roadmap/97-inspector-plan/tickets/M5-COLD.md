# M5-COLD — Cold graphs: list without waking, wake explicitly (full vertical)

**Status**: Implemented — merged to main (see `90-progress-log.md`).

Model: `claude-opus-5` (effort xhigh) · Track: full vertical (BE + FE) ·
Depends: M5-SEARCH merged (shares navigator UI files) · Parallel with: M5-NET

Files owned: `inspect/src/**`, `inspect/ui/**` (navigator + a cold-graph
screen), pilot launcher (to produce a suspended/parked graph on demand).

## Context

P6 makes browsing causal: subscribing wakes things. This vertical makes the
boundary an explicit, visible product feature — the v2 mockup's cold screen
("Read checkpoint (no attention)" vs "Wake to inspect") is the target UX,
scaled to what the kernel can honestly do today. The full
inspect-without-attention capability (cold reads from checkpoint/journal) is
a tracked kernel gap (Linear MRB-157) — deliver the minimal honest version,
do not build the checkpoint reader.

Seams: suspension/parking state via the registry/host
(`LocationRegistry.parkedFor`, lifecycle from M0's feed,
`ManagedHost.drainHost()/resumeHost()` — investigate what "cold" observably
means today: suspended cells / drained hosts / attention-parked cones — and
define the inspector's cold predicate from what exists, documented in KDoc).

## Implement

1. **BE**: extend `GraphList.lifecycle` to report `"cold"` for components
   whose cells are all suspended/parked (your documented predicate).
   Metadata-only — computing coldness must not touch cells. Add
   `POST /api/inspect/graph/{id}/wake` → resumes the component's
   hosts/cells via the existing resume seam, returns 202; the wake is
   **logged** as an SSE event (`lifecycle` events will follow naturally; add
   nothing new unless needed). Structure (topology) of a cold graph remains
   servable — it's registry metadata.
2. **FE**: cold cards/thumbnails dimmed with a ❄ tag (v2 mockup); entering a
   cold graph shows the cold screen: structure rendered ghosted, a notice
   ("cold — parked; state/flow unavailable without waking"), and an explicit
   "Wake to inspect" button (confirmation dialog: "Waking raises attention
   and resumes execution") that calls the wake endpoint and transitions to
   the live canvas as lifecycle events arrive. NO state/flow/error
   subscriptions while cold — selection shows descriptor only.
3. **Search integration**: data search now reports `coldSkipped` truthfully
   (count cold components' candidate cells); add an inline hint in the search
   UI when `coldSkipped > 0` ("N cold graphs skipped — wake to include").
4. **Pilot**: launcher flag to start one graph suspended/parked so the state
   is demonstrable.

## Exclusions

Checkpoint/journal cold reads (the kernel gap stays open — the cold screen
says "unavailable", not a fake preview). Auto-wake on any browse action.
Wake-then-re-suspend automation.

## Tests / acceptance

- BE: cold predicate unit test; wake endpoint resumes and lifecycle events
  flow; coldness computation provably subscription-free (leak-check
  technique from M1-EVAL).
- FE: cold gating (no observe calls while cold — mock-transport assertion),
  wake confirmation flow.
- `./gradlew :inspect:test`, `npm test` green; manual run with screenshots:
  cold card → cold screen → wake → live canvas.
