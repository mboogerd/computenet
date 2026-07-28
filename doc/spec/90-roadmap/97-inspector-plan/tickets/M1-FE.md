# M1-FE — Detail panel (all-properties) + state view + process-host hulls

**Status**: Implemented — merged to main (see `90-progress-log.md`).

Model: `claude-sonnet-5` (effort xhigh) · Track: frontend · Depends: M0-EVAL
merged · Parallel with: M1-BE (code against the contract + fixtures)

Files owned: `inspect/ui/**` only.

## Context

Read `10-target-v3.md` §"The v3 model" — this ticket builds its two core
pieces: the all-properties detail panel and the first canvas toggle. The v2
mockup (artifact link in that doc) shows the intended look; v3 differs in that
the detail panel always stacks ALL subsections (no perspective switching).
Contract: `20-api-contract.md` (`CellDetail`, `CellState`, `Value`,
`state.summary`, observe endpoints).

## Implement

1. **Detail panel** (right side, on node selection): stacked subsections per
   `10-target-v3.md` — (1) Descriptor & placement from `GET /cell/{ref}`;
   (2) State; (3) Flow placeholder ("arrives with the Flow milestone");
   (4) Errors placeholder. Selection persists across toggle changes.
2. **State subsection with subscription lifecycle**: on selection,
   `POST /cell/{ref}/observe` then `GET /cell/{ref}/state`; live-update from
   `state.summary` SSE events for the selected ref (refetch state on summary);
   on deselection or panel close, `DELETE .../observe`. Render the `Value`
   shape: `$table` as a data table (tombstone-style strikethrough when a row
   object carries `"tombstoned": true`), objects/lists as an indented tree,
   `$truncated` as a "showing N of M" note, `opaque` as a code block. Show the
   frontier stamp chip (`source · counter`) and staleness. Include the fixed
   footnote: "per-cell consistent — cross-panel alignment not guaranteed".
3. **Process-host hull toggle**: enable the "Process hosts" toggle — convex
   or padded-bbox hulls grouping nodes by `Node.host`, labeled, rendered
   beneath edges; recomputed only on structuralVersion change or host change.
   Leave "Network hosts" disabled (`net` is `"local"` until M5).
4. **Fixtures**: extend `fixtures/` with `cell-detail.json`, `cell-state-*.json`
   (one per Value shape) conforming to the contract; unit tests run against
   them.

## Exclusions

No errors/flow rendering (M2/M3), no state chips on canvas (that is part of
the State toggle, M3-FE picks it up alongside — NOT here; the State *panel* is
this ticket), no navigator. Do not edit the contract.

Correction for clarity: the canvas "State" toggle (per-cell chips) ships in
this milestone **only if** `state.summary` events are flowing for observed
cells — implement the chip rendering behind the toggle, driven purely by
received summaries (cells without an active observation simply show no chip).

## Tests / acceptance

- Vitest: subscription lifecycle (select → observe POST, deselect → DELETE —
  assert via a mock transport), Value renderer per fixture shape, hull
  grouping recompute rules.
- `npm test`, `npm run build` green.
- Manual with screenshots: selection shows all four subsections; state table
  live-updates while the demo mutates; hull toggle on/off.
