# SnapshotView — wave-aligned glitch-free multi-outlet edge

**Type:** missing primitive / DX
**Origin:** `:demo:skillmatch` folds six independent outlets into one HTTP/SSE
snapshot. Cross-references `doc/demo-findings.md` **F-5**.

## Origin of the idea

skillmatch's UI reads one JSON snapshot assembled from six outlets — `matches`,
`matchCounts`, `required`, `gap`, `supply`, `demand`. They update on separate
waves, so a snapshot can be **momentarily inconsistent**: the server test
already observed a state where a skill was counted as matched while the `gap`
view still listed it as uncovered, and the UI can flash contradictory panels.
Tests must `await` a **joint** predicate rather than any single view.

The kernel has the machinery for this — `GlitchFreeCell` buffers per-wave inputs
until a wave's edge set is complete, then replays them as one consistent group.
But `GlitchFreeCell<Api>` wraps **one** typed protocol (a single join's
frontier). There is no ergonomic way to apply glitch-freedom to a **hub that
folds N heterogeneous outlets** — precisely the observation edge every demo
builds. So the strongest consistency tool in the framework is unused exactly
where apps read state.

## What it is

An edge collector that subscribes to N heterogeneous outlets sharing a wave
lineage and delivers a **wave-aligned joint snapshot** — every view reflects the
same input wave, no cross-view glitch. It builds on `GlitchFreeCell`'s frontier
logic but presents a multi-inlet "snapshot" API instead of a single-Api wrapper.

```kotlin
val view = SnapshotView.build(host) {
    slot("matches", refs.matches)      // SetDelta<Match>
    slot("required", refs.required)    // MapDelta<String, Long>
    // ...
    onSnapshot { s -> broadcast(render(s)) }   // fires once per completed wave
}
```

Each `onSnapshot` sees all slots advanced to the same frontier — no slot can be
one wave ahead of another.

## Why it is a proper fit

- F-5 explicitly names this as a gap and even proposes it: "a glitch-free
  multi-inlet hub/collector idiom (or a `GlitchFree`-wrapped composite view
  cell) that delivers wave-aligned snapshots to the app edge."
- It reuses `civictech.cell.consistency.GlitchFreeCell` rather than inventing a
  parallel mechanism — the frontier is folded from the same in-band
  `EdgeOpen`/`EdgeClose` markers; wave order is per-source counter order.
- It pairs naturally with backlog 03 (`Materialize`): a `SnapshotView` is a
  multi-stream `View` whose `onChange` is wave-gated.
- Honest scope (ponytail): a static slot-set frontier, matching
  `GlitchFreeCell`'s existing "static link-set frontier" limitation (real
  upstream traversal needs multiplex ports, G-13) — so this is a composition of
  existing behavior, not new frontier theory.

## Solution sketch

Register one inlet per slot on a `GlitchFreeCell`-style buffer keyed by slot
name; fold the completeness frontier across all slots' `EdgeOpen`/`EdgeClose`.
When a wave completes on every participating slot, materialize each slot's
current value (via backlog-03 folds) and invoke `onSnapshot` once with the joint
map. Unwaved traffic passes through (matching `GlitchFreeCell`). `WaveMode.WAIT`
holds incomplete waves; `SKIP`/current mode degrades to today's behavior for
liveness-over-consistency callers.

## Expected inputs / outputs

- Input: N named outlets sharing a source/wave lineage.
- Output: `onSnapshot(Map<slot, materializedValue>)` fired once per completed
  wave, guaranteed cross-slot consistent.

Example guarantee: after `cskill ada sql` completes, the snapshot **never** shows
`matched:2` while `gap` still lists `sql` — both advance together or not at all.

## Acceptance criteria

- No snapshot exposes two slots at different wave frontiers (property test:
  drive random churn, assert every delivered snapshot equals a batch recompute
  from a single consistent input cut — the F-5 "matched vs gap" contradiction
  never appears).
- Exactly one `onSnapshot` per completed wave; no missed or duplicated waves
  under per-link FIFO.
- A dead-lettered contribution surfaces as `GlitchViolation` on an error outlet
  and advances the frontier rather than stalling forever (reuse
  `GlitchFreeCell`'s decided behavior).
- Refactor proof: skillmatch's server test drops its joint-`await` workaround
  and asserts single-snapshot consistency directly; the UI can no longer flash
  contradictory panels.
