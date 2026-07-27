# M1-BE — Cell detail + state preview endpoints

Model: `claude-opus-5` (effort xhigh) · Track: backend · Depends: M0-EVAL
merged · Parallel with: M1-FE

Files owned: `inspect/src/**` only (no kernel changes expected; if one proves
necessary, stop and flag the orchestrator first).

## Context

Read `AGENTS.md`, `10-target-v3.md`, `20-api-contract.md`, and the merged M0
code in `inspect/src/`. The state seams:
`kernel/src/main/kotlin/civictech/cell/observe/Observe.kt` —
`host.observe(ref, View...)` spawns an `ObserveCell` folding a delta stream
into an immutable snapshot with `current()` (torn-read-free) and
`onChange` (fires per settled effective change, plus immediate late-join
catch-up). `Stateful.snapshot(): Serializable`
(`kernel/.../cell/Stateful.kt`) is the fallback for cells without a usable
view; the host calls it on its own execution context — off-thread calls race
the fold, so route snapshot requests through the host (look at how
drain/checkpoint invoke it) rather than calling it from the HTTP thread.

## Implement

1. **`GET /cell/{ref}`** — `CellDetail` per contract: M0's node data plus
   attention band (if cheaply readable from the registry/host; else `null` —
   do not add kernel surface for it) and link counts from `TopologyIndex`.
2. **State reads with an explicit subscription model** (P6: browsing must not
   subscribe): `POST /cell/{ref}/observe` creates a server-side observation
   for that cell — `host.observe(...)` with the best-matching built-in `View`
   (`View.set()/map()/count()`, chosen from the cell's descriptor/type), and
   starts emitting `state.summary` SSE events on its `onChange`.
   `DELETE` releases it (unlink/despawn the sink — verify the release path in
   `Observe.kt` and actually free it; leaking sinks keeps attention raised).
   Idle safety net: auto-release after 5 min without a matching `GET state`.
3. **`GET /cell/{ref}/state`** — `CellState`: if an observation is active,
   encode `current()`; else fall back to a host-routed `Stateful.snapshot()`;
   else `kind: "unavailable"`. Include the frontier stamp when the view
   exposes one.
4. **`ValueEncoder`**: generic encoding of view snapshots / `Serializable`
   snapshots into the contract's `Value` shape (scalars, lists, maps, and the
   `$table` form for set/map-like states), with the contract's truncation
   rule. Design note: concord's neutral `Value` model
   (`concord/src/.../driver/`) is prior art worth reading, but `:inspect` must
   NOT depend on `:concord` — write the encoder in `inspect` and cover the
   kernel's common CRDT snapshot shapes (`SetCell`, `MapCell`, counters,
   operator ledgers) plus a safe reflective-toString last resort clearly
   marked `"opaque"`.

## Exclusions

No error/flow feeds (M2/M3). No wave-alignment across cells (accepted flicker;
F-5). No kernel edits without orchestrator sign-off. No `:concord` dependency.

## Tests / acceptance

- Observe lifecycle: POST → summaries flow → DELETE → sink released (assert
  no further onChange, and the sink cell despawned/unlinked); idle
  auto-release.
- State encoding golden tests: SetCell (with tombstones), MapCell, CounterCell,
  one operator cell, one snapshot-only cell, truncation at the limits.
- Snapshot thread-safety: state read of a snapshot-only cell routes through
  the host (test via a cell whose snapshot asserts its executing thread).
- `./gradlew :inspect:test` green; report includes a curl transcript of the
  observe → state → release flow against skillmatch.
