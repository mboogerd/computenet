# T20 — Inspector contract executable: shrink the fold blind spot, gate fixtures on `Dto.kt`

**Status:** not-started
**Model:** sonnet · **Escalate to:** opus
**Wave:** 1 · **Branches:** `ticket/T20`

## Context

`:inspect` exposes two read paths over a live graph: `POST /api/inspect/cell/{ref}/observe`
(open a folded, live `View` on a cell's outlet) and `GET /api/inspect/cell/{ref}/state`
(read it back). Both are gated by `Observations.viewFor` — a hand-maintained closed
world mapping a cell's generated `@CellBase` Api marker interface to a built-in
`View` fold (`inspect/src/main/kotlin/civictech/inspect/Observations.kt:298-345`).
A cell whose outlet emits a `SetDelta`/`MapDelta` shape but has no entry in
`SET_OUTLETS`/`MAP_OUTLETS` (or the single `GroupByApi` branch) answers `409`
on `observe` (`InspectorServer.kt:409-410`) and reports `CellState.UNAVAILABLE`
("unavailable") on `/state` — a cell that could be inspected, silently isn't.

The 2026-07-28 architecture audit (`doc/remediation/AUDIT-2026-07-28.md` §W4
item 3, finding B3 in `doc/architecture-decisions.md`) added a guardrail for
this class of bug: `ObservationsCompletenessTest`
(`inspect/src/test/kotlin/civictech/inspect/ObservationsCompletenessTest.kt`)
reflectively walks every `@CellBase` cell on the classpath, and for every one
whose outlet raw type includes `SetDelta`/`MapDelta`, asserts `Observations.viewFor`
resolves a fold. It found three cells that are foldable but blind, seeded into
its `knownBlind` set (lines 38-42): `PresenceCountCell`, `MergeableGroupByCell`,
`ShardCell`. The test's amendment header (lines 24-29) is explicit: `knownBlind`
only shrinks, a stale entry (fold landed but the entry wasn't deleted) fails the
test by design, and the test itself must not be weakened.

Separately, the inspector's frontend (`inspect/ui/`) has its own model of the
wire contract in `inspect/ui/src/api/types.ts`, hand-kept in sync with
`inspect/src/main/kotlin/civictech/inspect/Dto.kt` (the `@Serializable` Kotlin
DTOs the backend actually encodes). `inspect/ui/fixtures/` (21 files, confirmed
by directory listing — not 22) is the FE's only concrete stand-in for real
server responses, consumed by the FE's own fixture-driven tests. The inspector
plan's progress log records five silent fixture/type drifts across six
milestones (`doc/remediation/AUDIT-2026-07-28.md` finding B5), each caught only
by a since-retired manual EVAL pass; nothing on the backend validates a fixture
file's shape against `Dto.kt`.

`inspect/ui/src/api/types.ts:26` also carries a stale comment on `Node.net`:
`// network host / peer id — "local" until M5`. M5-NET has landed (the audit's
Declined table in `doc/architecture-decisions.md`, row `types.ts net:
string|null diverges from Dto.kt non-null`, confirms the wider `string | null`
type itself is fine — every consumer coalesces defensively — and that the only
real defect is this now-stale comment).

## Problem

1. `Observations.viewFor` (`Observations.kt:336-344`) is a closed `when` over
   two hand-maintained `Class<*>` lists, `SET_OUTLETS` (line 306) and
   `MAP_OUTLETS` (line 312), plus a single `GroupByApi` branch. None of the
   three `knownBlind` cells appear in either list:
   - `PresenceCountCell` (`kernel/src/main/kotlin/civictech/cell/data/op/PresenceCountCell.kt:118-122,139-140`)
     implements a real Api marker interface, `PresenceCountApi<E>` (declared in
     the same file, **not** `@CellBase`-annotated itself — see point 2 below),
     whose `outlet: Subscribe<Propagate<MapDelta<E, Int>>>` (line 121) is a
     `MapDelta` producer.
   - `MergeableGroupByCell<E, K, A : Serializable>`
     (`kernel/src/main/kotlin/civictech/cell/data/op/MergeableGroupByCell.kt:45-58`)
     declares `override val outlet = registerPort("outlet", FanOutlet.create<Propagate<MapDelta<K, A>>>())`
     (line 54-55) — a `MapDelta` producer, generic over an arbitrary
     commutative-associative accumulator `A` (the class KDoc: "the mergeable
     sibling of `GroupByCell`", CP-G1).
   - `ShardCell<E>` (`kernel/src/main/kotlin/civictech/cell/partition/ShardCell.kt:44-49,91`)
     declares `override val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<E>>>())`
     (line 91) — a `SetDelta` producer.

2. **Ground truth correction to the fold-table shape**: unlike every cell
   already in `SET_OUTLETS`/`MAP_OUTLETS` (e.g. `MapApi`, `JoinApi`,
   `GroupByApi` — all declared `@CellBase interface XxxApi<...>` in their
   respective files, e.g. `GroupByCell.kt:18-22`), `MergeableGroupByCell` and
   `ShardCell` declare **no separate `Api` marker interface at all**. Their
   class headers are `class MergeableGroupByCell<...>(...) : Cell, Stateful,
   Replicable<MapDelta<K, A>>` and `class ShardCell<E>(...) : Cell, Stateful,
   Replicable<SetDelta<E>>, Partitioned` — plain `Cell` implementations with
   `registerPort` calls, processed only by the `@Key` port-id generator
   (confirmed: `kernel/build/generated/ksp/main/kotlin/.../MergeableGroupByCellPorts.kt`
   and `.../ShardCellPorts.kt` exist as generated *port-id* objects, but there
   is no generated `@CellBase` descriptor keyed to a `MergeableGroupByApi` or
   `ShardApi`). `viewFor(type: Class<*>)` is called with the **concrete cell
   class** (`Observations.kt:164-168`: `type = registry.describe(ref)`, tested
   via `ContractRegistry.cellDescriptor(type)`), and `SET_OUTLETS`/`MAP_OUTLETS`
   membership is tested with `it.isAssignableFrom(type)`. Because neither cell
   implements a matching Api interface, the two entries this ticket adds for
   them must be the **concrete classes themselves**
   (`MergeableGroupByCell::class.java`, `ShardCell::class.java`), not an "Api"
   type — there isn't one to reference. `PresenceCountCell` is the one of the
   three that does have a real Api type to add: `PresenceCountApi::class.java`.
   (`ObservationsCompletenessTest.kt`'s `knownBlind` set already keys all three
   entries on the concrete cell fqn, e.g. `"civictech.cell.data.op.MergeableGroupByCell"`
   — line 40 — consistent with this: `CellDescriptor.fqn` is always the
   concrete class, per `nature/src/main/kotlin/civictech/nature/ContractDescriptor.kt:292-293`.)

3. `MergeableGroupByCell`'s fold choice is a genuine judgment call, not
   mechanical: `Observations.kt:317-330`'s KDoc explains the existing
   `GroupByApi` branch resolves to `View.count()` rather than `View.map()` —
   not because a `GroupByApi` cell's aggregate is literally a count, but
   because `CountView` is a `MapView` with a zero-defaulting accessor, so "the
   fold is identical either way — this only keeps the sink honest about what
   it is folding." `MergeableGroupByCell` is structurally `GroupByCell`'s
   mergeable sibling (same per-key generic-aggregate shape, `MapDelta<K, A>`
   out), so the same argument for routing it through the count-labeled branch
   applies — but doing so means either widening the `GroupByApi` `when` branch
   to also match `MergeableGroupByCell::class.java`, or accepting the plainer
   `MAP_OUTLETS` label. Both produce the same wire behavior (per the KDoc);
   only the sink's self-description differs. Left to the implementer, per
   point 5 below.

4. No test on the backend ever decodes an `inspect/ui/fixtures/*.json` file
   through `Dto.kt`. The fixtures are hand-authored/hand-updated JSON; a field
   rename, addition, or removal in `Dto.kt` has no automated backend-side check
   that the fixtures still match — the exact class of defect the audit's
   five-drifts-in-six-milestones history names.

5. `inspect/ui/src/api/types.ts:26`: `net: string | null; // network host / peer
   id — "local" until M5` is stale — M5-NET landed and the type itself
   (`string | null`) was separately adjudicated correct in the audit's Declined
   table; only the comment is wrong.

## Solution direction

1. In `Observations.kt`'s companion object (lines 287-345):
   - Add `PresenceCountApi::class.java` to `MAP_OUTLETS` (line 312-315). Import
     `civictech.cell.data.op.PresenceCountApi`.
   - Add `ShardCell::class.java` to `SET_OUTLETS` (line 306-310). Import
     `civictech.cell.partition.ShardCell`.
   - Add `MergeableGroupByCell::class.java` to either `MAP_OUTLETS` or the
     `GroupByApi` count branch (`viewFor`'s `when`, line 339) — implementer's
     call per point 3 above; whichever is chosen, say which and why in the
     completion report. Import `civictech.cell.data.op.MergeableGroupByCell`.
   - Delete all three entries from `ObservationsCompletenessTest.knownBlind`
     (lines 38-42) in the same change — the test's own header requires this,
     and `coveredKnownBlind` (line 91, asserted empty at line 103) fails the
     build on a stale entry.
2. Extend coverage for at least one of the three folds beyond the guardrail
   itself, following the two existing patterns in
   `inspect/src/test/kotlin/civictech/inspect/ObservationsIdleTest.kt`:
   - The cheap, mechanical check: add a line to
     `` `the built-in fold is chosen from the cell's generated API, never guessed` ``
     (lines 116-127) asserting `Observations.viewFor(...)` is non-null for the
     new class(es).
   - At least one full behavioral check in the shape of
     `` `an observation nobody has read for five minutes is released` `` (lines
     36-53) or `` `an outlet with no built-in fold is refused, and spawns
     nothing` `` (lines 95-104): spawn a `PresenceCountCell` (or whichever of
     the three is most convenient to construct and drive — `PresenceCountCell`
     needs no constructor arguments beyond a `CellRef` default, unlike
     `MergeableGroupByCell`/`ShardCell` which need `keyOf`/`accumulate`/`merge`
     or an `Interest`), call `observations.start(cell.ref) shouldBe true`, and
     confirm a state read reflects the folded `MapDelta`/`SetDelta` content —
     i.e. the acceptance criterion's "POST /observe on a
     `PresenceCountCell`-backed graph succeeds" belongs here, driven directly
     through `Observations` (as `ObservationsIdleTest` already does), not
     through the HTTP layer.
3. New test, e.g. `inspect/src/test/kotlin/civictech/inspect/FixtureContractTest.kt`:
   for every file in `inspect/ui/fixtures/*.json`, strict-decode it
   (`Json { ignoreUnknownKeys = false }` — do **not** reuse `Dto.kt`'s
   `inspectorJson`, which sets `encodeDefaults = true` for encoding but leaves
   `ignoreUnknownKeys` at its default `false`, so a fresh strict `Json` instance
   in the test is clearer intent even if behaviorally equivalent; the point is
   an unknown key must fail) through the `Dto.kt` `@Serializable` type it
   represents. Hand-write the file→type mapping in the test — do not infer it
   from the filename. Candidate mapping (verify against each fixture's actual
   shape before committing to it — some names are ambiguous):
   - `topology.json`, `topology-multihost.json`, `topology-nets.json` → `TopologySnapshot`
   - `cell-detail.json` → `CellDetail`
   - `cell-state-scalar.json`, `cell-state-table.json`, `cell-state-tree.json`,
     `cell-state-opaque.json`, `cell-state-truncated.json`,
     `cell-state-unavailable.json` → `CellState`
   - `errors.json` → `ErrorSnapshot`
   - `error-event-dead-letter.json`, `error-event-parked.json`,
     `error-event-restart.json` → `Event` (the SSE envelope; `payload` is a
     `JsonObject`, so the envelope decodes strictly even though this test does
     not separately verify the payload's inner shape against
     `DeadLetterRow`/`ParkedRow`/`RestartRow` — note that limitation in the
     completion report rather than silently expanding scope)
   - `flow-rates.json` → `FlowBatch`
   - `graphs.json`, `graphs-cold.json` → `GraphList`
   - `search-name.json`, `search-problems.json`, `search-data.json`,
     `search-data-cold.json` → `SearchResult`
   Assert the mapped file set equals the actual `inspect/ui/fixtures/*.json`
   directory listing (e.g. compare a `Set<String>` of filenames), so a new
   fixture added later without a mapping entry fails the test instead of
   silently passing unchecked.
4. `inspect/ui/src/api/types.ts:26`: fix only the comment. Do not change the
   `net: string | null` type — that width was separately adjudicated correct.

If any fixture fails strict decode against the `Dto.kt` type you assign it:
**stop and report it as a finding**, per the file-claim note below — do not
edit the fixture to make the test pass. The audit's five-drift history is
exactly a silent fixture/type divergence; discovering a sixth here and fixing
it silently would repeat the pattern the guardrail exists to end.

## Files expected to touch

- `inspect/src/main/kotlin/civictech/inspect/Observations.kt` — three fold-table
  entries (imports + `SET_OUTLETS`/`MAP_OUTLETS`/`GroupByApi` branch).
- `inspect/src/test/kotlin/civictech/inspect/ObservationsCompletenessTest.kt` —
  shrink `knownBlind` to `emptySet()`.
- `inspect/src/test/kotlin/civictech/inspect/ObservationsIdleTest.kt` (or a new
  test file under `inspect/src/test/kotlin/civictech/inspect/`) — coverage
  extension for the newly-folded cells.
- `inspect/src/test/kotlin/civictech/inspect/FixtureContractTest.kt` — new file,
  the strict-decode fixture gate.
- `inspect/ui/src/api/types.ts` — the `Node.net` comment only (line 26).

Touching files outside this list: note it in the completion report rather than
expanding silently. Parallel work is scheduled on this claim.

## Read first

- `doc/remediation/AUDIT-2026-07-28.md` §W4 items 2-3 — the decided direction
  this ticket implements.
- `doc/architecture-decisions.md` findings B3, B5 and guardrail G3 — why the
  guardrail exists and its amendment policy.
- `inspect/src/main/kotlin/civictech/inspect/Observations.kt:287-345` — the
  fold table and its `viewFor` dispatch; read the whole companion object, not
  just the two lists, since the `GroupByApi` branch is where the
  `MergeableGroupByCell` judgment call lands.
- `inspect/src/test/kotlin/civictech/inspect/ObservationsCompletenessTest.kt` —
  the guardrail; its amendment header is binding (`knownBlind` only shrinks;
  do not weaken the assertions).
- `inspect/src/test/kotlin/civictech/inspect/ObservationsIdleTest.kt` — the
  patterns to extend: the mechanical `viewFor` list (lines 116-127) and a full
  spawn/observe/sweep test (lines 36-53) for the behavioral check.
- `inspect/src/main/kotlin/civictech/inspect/Dto.kt` — every `@Serializable`
  DTO the fixture contract test decodes fixtures into.
- `inspect/ui/fixtures/` — all 21 files; read each one before assigning it a
  `Dto.kt` type in the test's mapping.
- `inspect/ui/src/api/types.ts:20-30` — the stale `net` comment and its
  surrounding `Node` interface, for context on why the type itself is not
  changing.

Do not modify: `kernel/**` (the three cells are correct as written; only the
inspector's fold table changes), `inspect/src/main/kotlin/civictech/inspect/InspectorServer.kt`
(owned by ticket T19), `inspect/src/main/kotlin/civictech/inspect/Dto.kt`, and
the fixture files themselves in `inspect/ui/fixtures/` (a failing strict decode
is a finding to report, never a fixture edit to make the test pass).

## Acceptance criteria

- [ ] `ObservationsCompletenessTest.knownBlind` is `emptySet()` and
      `ObservationsCompletenessTest` passes.
- [ ] `Observations.viewFor` resolves a non-null fold for `PresenceCountApi`,
      `MergeableGroupByCell`, and `ShardCell`.
- [ ] A test drives `Observations.start` (or the equivalent full path) on a
      `PresenceCountCell`-backed cell and asserts it succeeds (`true`, not the
      `409`/`unavailable` behavior the class exhibits today).
- [ ] `FixtureContractTest` (or equivalently named) strict-decodes every file
      in `inspect/ui/fixtures/*.json` through its mapped `Dto.kt` type, and its
      file→type mapping is asserted to cover exactly the fixture directory's
      current contents (a new unmapped fixture fails the test).
- [ ] `inspect/ui/src/api/types.ts:26`'s comment no longer claims `net` is
      `"local" until M5`.
- [ ] `./gradlew :inspect:test` passes.
- [ ] No unrelated files in the diff.

## Verify

```bash
./gradlew :inspect:test
```

## Report on completion

- Checks run and their results.
- Which fold branch `MergeableGroupByCell` landed in (`MAP_OUTLETS` or the
  widened `GroupByApi` count branch) and why.
- Files actually touched, and any not in the claim above.
- Whether any `inspect/ui/fixtures/*.json` file failed strict decode against
  its assigned `Dto.kt` type — if so, which file, which type, and the specific
  mismatch, left unfixed per the file-claim instruction.
- Anything specified here you could not do, and why.
