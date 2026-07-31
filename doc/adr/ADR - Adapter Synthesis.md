# ADR — Adapter Synthesis: an `Adapt` arm for nature reconciliation (FU-4, phase 1)

**Status**: ACCEPTED (2026-07-31) — NO-GO ratified: refuse-only linking stands;
revisit when ≥3 real hand-written wavers accumulate in real graphs (§e).
Phase-1 deliverable only: design + spike. No production code changes.
Analysis pinned to `main` @ `4cfbb83`.

**Spike**: `AdaptWaveParticipationSpikeTest` — ran green in the FU-4 worktree
but was never merged with this ADR; the branch is gone and no copy survives on
`main` (verified 2026-07-31). §d below is its record; recreate from §d if
phase 2 is ever triggered. (The FU-7 addendum's spike, which does survive,
demonstrates the same registry pattern live.)

## Question

When an outlet's nature is below what an inlet requires on some axis, should
`NatureNegotiation.reconcile` be able to name an adapter that lifts the producer
— `Direct | Adapt(candidate) | Refuse` — instead of refusing outright?

## (a) The adaptable-axis table

An axis is adaptable iff a **semantics-preserving lift** exists: a wrapper that
raises the offered level without changing what the values *mean*.

| Axis | Lift required | Adaptable? | Rationale |
|---|---|---|---|
| `WAVE_PARTICIPATION` (UNWAVED→WAVED) | re-origination | **Yes** | The lift is exactly `FanOutlet.originate`: relay each emission as a fresh wave from the adapter's own source lane. Values unchanged, per-link FIFO preserved; the adapter adds per-emission wave structure — precisely what a hand-written waver would do. It cannot (and need not) invent cross-source alignment. Proven end-to-end by the spike. |
| `INSTANCE_SCOPING` (SINGLETON→INTEREST_SCOPED) | per-payload slicing | **Only with developer code** | Making a delta `Scoped` requires knowing how to slice *that payload type* by key — there is no generic lift. A registry entry could carry a developer-supplied slicer, but then the developer has written the adapter anyway; the registry only standardizes its insertion. Stays `Refuse` in phase 1. |
| `MERGE_IDEMPOTENCE` (NON_IDEMPOTENT→IDEMPOTENT) | none exists | **No** | A counted accumulator cannot be made idempotent without changing its semantics. (Dedup-by-delivery-tag is a real technique but needs unbounded tag memory and changes delivery semantics — a redesign, not an adapter.) Hard refusal, guarded by the spike's control. |
| `MONOTONICITY` (NON_MONOTONE→MONOTONE) | none exists | **No** | Monotonicity is a property of the value stream itself; any wrapper that forces it (e.g. running max) changes the values. Hard refusal. |
| `OWNERSHIP` (SHARED→EXCLUSIVE) | none exists | **No** | An adapter cannot conjure exclusivity — upstream aliases may exist; cloning into `Owned` forks the payload identity. Hard refusal (and the instance-set/SPSC refusals in `admitToInstanceSet` are structural, not link-lifts). |
| `COLOR` | n/a | n/a | Not a link-flow axis (placement property, validated at spawn). |

Net: exactly **one** axis has a generic, semantics-preserving lift.

## (b) Registry, not synthesis; offered, not automatic

- **Registry over synthesis.** An `Adapt` is a *lookup* in a registry of
  developer-registered adapter factories keyed by axis (`(axis, fromLevel,
  toLevel)` if an axis ever grows >2 levels). No code generation. The registry
  validates a candidate before naming it: the adapter's own offered vector must
  reconcile `Direct` into the inlet (single-hop, no chain search — open
  question 5 resolved as *single-hop only*).
- **Offered over automatic.** `Adapt(candidate)` is a *richer refusal*, not a
  third acceptance path: the link handshake still returns `Rejected` (now
  naming the available adapter in its reason), and insertion is an explicit
  wiring choice by the developer/DSL. This preserves "no silent bridging"
  verbatim — non-opting graphs see byte-identical behavior, including the
  rejection strings, until a caller explicitly consults the registry.

## (c) Shape and insertion (what phase 2 would build)

```kotlin
sealed interface Reconciliation {
    data object Direct : Reconciliation
    /** A validated, registered lift exists; NOT inserted — offered. */
    data class Adapt(val candidate: AdapterCandidate) : Reconciliation
    data class Refuse(val mismatch: NatureMismatch) : Reconciliation
}
```

- `reconcile` keeps its pure 2-arg form returning `Direct | Refuse`; an
  overload (or sibling `reconcile(offered, required, registry)`) adds the
  `Adapt` arm. `Link.handshake` maps `Adapt` to `Rejected` with the candidate
  named — zero behavior change at links.
- Insertion is a helper the wiring code calls explicitly, e.g.
  `linkVia(producer, inlet, candidate)`: spawn the adapter cell, link
  producer→adapter (Direct — the adapter's inlet requires nothing),
  adapter→inlet (Direct — validated at registration).
- **Determinism / PN-1**: the adapter's `CellRef` derives from the link, not
  from randomness — `UUID.nameUUIDFromBytes("adapt:$axis:${from}:${to}")` — so
  re-wiring after restart/replay reproduces the same ref, and its outlet's
  wave lane follows the usual epoch rules (fresh epoch at cold start, exactly
  like any cell).
- **Placement**: co-host with the *consumer* (the inlet whose requirement it
  satisfies), so a bridged producer edge terminates exactly where a hand-wired
  waver would.
- **Multi-axis mismatch**: phase 1/2 refuse outright (reconcile already reports
  the first refusing axis; no chains, resolving open questions 3/5
  conservatively).

## (d) The spike

`AdaptWaveParticipationSpikeTest` (all test-local, no production edits):

1. **Pinned today**: unwaved producer → ALIGN (WAVED-requiring) inlet is a
   typed refusal on `WAVE_PARTICIPATION` (and per PN-0a/PN-12, bypassing it
   the frontier drops the traffic).
2. **Spike**: a spike-local registry names a **waver** (a relay whose stamped
   vector offers WAVED and whose relay is `outlet.originate { … }`); the test
   inserts it explicitly; the ALIGN inlet then receives all values **in order,
   through the wave path** — every delivery carries a wave id from the waver's
   single source lane in counter order, `unmatchedDrops == 0`.
3. **Control**: `MERGE_IDEMPOTENCE` still refuses through the same spike
   reconcile — the registry has an adapter, but for a different axis.

The lift is ~15 lines including the registry — feasibility is not in doubt.

## (e) Recommendation: **NO-GO** (keep refuse-only; revisit on evidence)

The original gate was: build adapter synthesis only on post-evidence proof of
repeated hand-stacked adapters. Re-checked at `4cfbb83`:

- `grep -ri 'adapter|waver' demo/**/*.kt` → **zero** hand-written wavers or
  scopers across all seven demos (agora, exchange, shopping, skillmatch,
  slotfinder, tiering, backlog-triage).
- The whole per-node composition run hit no case where a refusal forced a
  manual adapter — the refusals introduced by PN-12 surfaced real wiring bugs
  (the silent drops), not missing lifts.
- Only one axis is even generically adaptable, and its lift is small enough
  that a developer who needs one writes it in ~10 lines today.

So the demand gate still reads zero. Ship nothing; keep `Direct | Refuse`.
This ADR + spike are the paid-down design cost: when N (suggest N ≥ 3) real
hand-written wavers accumulate in real graphs, phase 2 is a small, pre-agreed
step — the `Adapt` arm, the registry, and `linkVia`, exactly as sketched in
(c), with the spike promoted to `AdaptWaveParticipationTest`.

**Explicitly out of scope regardless of go/no-go** (per the ticket): multi-axis
chains, genuine synthesis, automatic silent insertion.

---

# Addendum — FU-7 delta↔snapshot adapters (phase 1)

**Status**: PROPOSED — awaiting review. Phase-1 deliverable only (this
addendum + spike); zero production code changes. Analysis pinned to `main`
@ `f92a731`.

**Spike**: `kernel/src/test/kotlin/civictech/cell/port/AdaptDeltaSnapshotSpikeTest.kt`
(green — all three tests: the pinned-today miswire, the registry-selected fold
adapter converging, the unregistered-pair control).

*(Housekeeping observation, not a change to the ADR above: the FU-4 spike file
this ADR cites as "retained", `AdaptWaveParticipationSpikeTest.kt`, is absent
from `main` at `f92a731` — worth restoring or amending the citation.)*

## (a) Demand sweep (the FU-4 lesson, run first)

Sweep of all seven demos for hand-written fold/diff/scan glue between
delta-typed cells (`SetDelta`/`MapDelta`/`CounterDelta` producers) and
snapshot-typed consumers, agora's backend↔frontend boundary included.
Kernel-view-layer usage (`data/view/` — `SetView`/`MapView`/`MapDiffPublisher`;
`observe/` — `host.observe` + `View`) is *not* glue, per the ticket.

| # | Site | What it does | Verdict |
|---|---|---|---|
| 1 | `demo/backlog-triage/src/main/kotlin/civictech/demo/backlogtriage/RankingCells.kt:134-144` (`MetaRankCell`) | Seven runtime `FanInlet<Propagate<MapDelta<String, Double>>>`, each hand-folding `puts`/`removals` into a per-source snapshot map (`:138-141`) because `Borda.combine` (`:151`) consumes full maps | **REAL** — the one in-graph delta→snapshot fold-glue instance. A fold adapter would let it declare snapshot inlets. Did not use `MapView` though it would drop in |
| 2 | `RankingCells.kt:79-96` (`RatingCell.onInlet`) | Hand-folds `SetDelta<Pref>` tag algebra into live-membership *transitions* (0→live, live→0) to drive `engine.add`/`retract` | Adjacent, **not counted** — the consumer wants transition *events*, not snapshots; a `Set<Pref>` snapshot stream would force it to re-diff. (It is, separately, a partial re-implementation of `TagState`/`SetView`) |
| 3 | `demo/tiering/src/test/kotlin/civictech/demo/tiering/TieringPipelineTest.kt:33-41` | Test subscriber hand-folds `MapDelta` → map | Not counted — observation-edge subscriber; `MapView` exists and covers it verbatim; no cell-to-cell link involved |
| 4 | `demo/slotfinder/src/test/kotlin/civictech/demo/slotfinder/SlotFinderPipelineTest.kt:33,40-45` | Same test uses `SetView` for set outlets (`:31-32`) but hand-folds the `MapDelta` outlet | Not counted — same as #3 |
| 5 | `demo/backlog-triage/src/test/kotlin/civictech/demo/backlogtriage/RankingCellTest.kt:44` | Test helper folding a delta list into a map | Not counted — same as #3 |
| 6 | `demo/exchange/src/test/kotlin/civictech/demo/exchange/ExchangeCompositionExitTest.kt:352,392-393,626,684-685,698` | Probe cells/aggregations over deltas (region sums, release logs, a delta→delta filter, effective-add extraction) | Not counted — derived aggregates and delta→delta transforms, not fold-to-snapshot for a snapshot-typed consumer |
| 7 | App boundaries of shopping (`Main.kt:192-194,206,226`), exchange (`Main.kt:201`), tiering (`TieringApp.kt:120-125`), skillmatch (`SkillMatchApp.kt:239-250`), slotfinder (`SlotFinderApp.kt:120`) | `host.observe` + `View.set`/`View.map` / `observeAll` | Sanctioned view layer — exactly what it exists for |
| 8 | Agora backend↔frontend (`demo/agora/src/main/kotlin/civictech/agora/cell/CredenceView.kt:22-43`, `AgoraService.kt:59`) | App-domain deltas (`CredenceUpdate`, `StanceDelta`, `InfluenceDelta` — **no kernel delta types at the boundary at all**) folded by a `View` implementation inside the kernel `ObserveCell` | Sanctioned — the `View` interface is the extension point |

**Count of real instances: 1** (MetaRankCell — one cell, one pattern, seven
runtime inlet instantiations, one demo).

Two structural findings sharpen the count:

- **Zero snapshot-typed port contracts exist anywhere in `demo/**`** — no
  `Propagate<Set<T>>` / `Propagate<Map<K, V>>` inlet or outlet at all. The
  ticket's premise ("the link doesn't typecheck, so the developer hand-writes a
  fold") has never literally occurred: developers pre-adapt the *contract* to
  deltas at authoring time, so the refusal is invisible. The demand hides in
  contract design (MetaRankCell's delta inlets wrapping a snapshot-hungry
  combine), not in visible glue cells.
- **The refusal site is not where the ticket assumes.** At the typed
  `link()` veneer (`kernel/.../cell/host/TypedLink.kt`) the mismatch is a
  compile error. On the stringly `connect` path there is *no refusal at all*:
  `checkPayload` (`kernel/.../cell/link/Handshake.kt:90-99`) compares erased
  `Api` classes, and `Propagate<SetDelta<T>>` vs `Propagate<Set<T>>` both erase
  to `Propagate` — the link reports `Connected` and the first delivery dies as
  a `ClassCastException` far from the connect (the same-wrapper residual pinned
  by `PayloadTypeCheckTest`'s KNOWN GAP test; spike test 1 pins the FU-7 shape
  of it). A registry consulted "at the refusal" therefore has, today, no
  runtime refusal to hang off — see (e).

## (b) Keying: a sibling registry, not the FU-4 axis registry

Confirmed sibling (the ticket's expectation). The FU-4 registry is keyed by
`(axis, fromLevel, toLevel)` over the *closed* vocabulary of nature axes; this
one is keyed by contract pair `(fromApi, toApi)` — in practice the payload
*type-constructor* pair (`SetDelta → Set`, `MapDelta → Map`, …) with the
element types required equal, since the adapters are parametric in `T`/`K,V`
and never inspect elements. The insertion helper differs too: FU-4's waver
relays the *same* payload type (the lift is pure wave structure), while a
fold/diff adapter **changes the payload type across itself** (inlet
`SetDelta<E>`, outlet `Set<E>`), so validation means checking the candidate's
two declared payload shapes against the pair, not reconciling one nature
vector. The `Adapt`-is-offered-never-silent posture carries over verbatim:
lookup names a candidate, insertion is an explicit wiring act, non-consulting
graphs are byte-identical.

**Erasure prerequisite** (new, and load-bearing): the pair cannot be recovered
from live port objects — `FanOutlet.clazz`/`FanInlet.clazz` are both
`Propagate::class.java` for every delta and every snapshot payload. The spike
keys by caller-supplied class tokens (`SetDelta::class`, `Set::class`), which
works for an explicitly-consulted registry but means a phase-2 registry can
only be consulted *from the handshake* after ports carry a declared payload
witness independent of `Api` erasure — the exact open residual in
`doc/remediation/COVERAGE.md` ("same-wrapper payload mismatch still
unchecked"). That residual is a hard dependency of any GO here.

## (c) The two canonical adapters

**fold (deltas → snapshots)** — the direction with the demand instance, and
the one the spike builds:

- Subscribe `Propagate<D>`, accumulate, emit `Propagate<S>`. The
  payload-generic ingredient is **not** a new `Replicable` or `Magnitude`
  bound: it is exactly the kernel's existing consumer-side fold — a
  `View<D, S>`-shaped value (`SetView` for `SetDelta`, `MapView` for
  `MapDelta`, a sum for counter deltas). The registry entry carries the
  fold/view factory; `Replicable` is producer-side merge and `Magnitude` is
  irrelevant here.
- **Cadence: every *effective* delta** (phase 1 choice). `SetView.apply` /
  `MapView.apply` already return effective-change, so tag churn emits nothing
  (spike asserts this). Wave-aligned cadence is real but is the composite case
  — it requires the adapter to participate in alignment, i.e. the FU-4 wave
  lift stacked on the type lift. Punted, per single-hop-only.
- Late joiners: catch-up-on-link with the current snapshot (G-22), exactly as
  `RatingCell` does with its `catchUpDelta()`.

**diff (snapshots → deltas)** — no observed demand instance; sketched for
symmetry:

- Subscribe `Propagate<S>`, diff against the previously published snapshot,
  emit `Propagate<D>`. The map shape already exists in the kernel as
  `MapDiffPublisher` (`publishAll`); a registry entry would carry a
  differ `(prev: S, next: S) → D?`.
- The asymmetry that matters: for **tagged** deltas (`SetDelta`'s OR-set
  algebra) a diff must *mint causal tags* — add-tags unique per add instance,
  ref-derived for replay stability, exactly as `SetCell` mints them. A diff
  adapter is therefore writer-like: it re-originates causal identity, the
  type-level analog of FU-4's re-origination waver. It is honest only as an
  explicit, registered, developer-chosen insertion — never inferable.

## (d) Interaction with natures

- A fold adapter's outlet is **UNWAVED unless it re-originates** (the FU-4
  waver observation, unchanged): its reactive `propagate` continues the
  incoming context; folding into an ALIGN inlet needs both the type lift and
  the wave lift (`FanOutlet.originate`). That stacked composite is exactly the
  multi-adapter chain phase 1 punts on — noted, not built. The spike's
  consumer is a plain fan-in inlet, so the plain reactive path suffices.
- The fold also *changes the stream's merge character*: a tagged delta stream
  is idempotent/commutative under the tag algebra; the emitted snapshot stream
  is last-writer-wins whole values. The adapter's stamped nature vector must
  declare the snapshot side honestly (MERGE_IDEMPOTENCE: a re-delivered
  snapshot folds to no change, but ordering is load-bearing) — a phase-2
  detail to resolve against `NatureNegotiation`, flagged here only.

## (e) Recommendation: **NO-GO for phase 2 now** (demand ledger: 1 of ≥3)

Demand is real but thin — one in-graph instance (`MetaRankCell`), against the
same evidence gate FU-4 set (≥3 real hand-written instances in real graphs).
Everything else the sweep found is either the observation edge, which the
existing view layer (`host.observe` + `View`, `SetView`/`MapView`/
`MapDiffPublisher`) already serves — including agora's entire
backend↔frontend boundary — or delta→delta/derived-aggregate code no fold
adapter would absorb. Per the FU-4 precedent, a zero-demand exit would have
required no spike; demand here is non-zero, so the spike was built and is
green, retained as executable documentation of the phase-2 shape.

Ship nothing now. Revisit triggers (any one suffices):

1. The ledger reaches **≥3** in-graph delta→snapshot folds (or snapshot-typed
   consumer contracts forced into delta shape — count MetaRankCell as #1).
2. The declared-payload-witness residual closes for its own reasons (T08
   COVERAGE row) — the erasure prerequisite in (b) is then free, and the
   registry consult can hang off the real runtime refusal it creates.
3. A demo genuinely needs the diff direction (snapshot-writer feeding a
   delta-consuming mesh), which no view-layer type covers today.

At GO, phase 2 is pre-agreed by this addendum + spike: the sibling
pair-keyed registry, the fold adapter generic over a `View<D, S>` factory,
explicit insertion (`linkVia`-style), offered never silent, single-hop only —
with the spike promoted to `AdaptDeltaSnapshotTest`.

**Explicitly out of scope regardless** (per the ticket and FU-4): adapter
stacking (type lift + wave lift), automatic/silent insertion, synthesis.
