# Demo findings — kernel requirements discovered by the demo apps

> **Status**: living document — the demo modules (`:demo:slotfinder`, `:demo:skillmatch`,
> `:demo:tiering`) exist to showcase the incremental dataflow layer **and** to surface
> kernel gaps: bugs, developer-experience friction, missing reusable incremental data
> structures, and missing incremental operations. Every non-trivial workaround in a demo
> should leave an entry here, formatted as a candidate G-entry for
> [91-gap-analysis.md](spec/90-roadmap/91-gap-analysis.md).
>
> Entry format: **observation** (what the demo needed) → **why it's a gap** (what the
> kernel lacks) → **proposed shape** (candidate operator/mechanism).

## F-1 — No map-stream fusion / per-key combine-latest / outer join — **CLOSED**

**Closed**: promoted to kernel `CombineLatestCell` (`civictech.cell.data.op.CombineLatestCell`,
restructure RS-5.4), generalizing the `FuseCell` prototype described below.

**Observation**: two demos independently need to combine two `MapDelta` streams per key:
`:demo:tiering` fuses tier-average and preference-average maps into one score
(prototyped as the app-level `FuseCell`), and `:demo:skillmatch` compares per-pair match
counts against per-job required counts to derive qualification (computed in the hub).
**Why it's a gap**: `JoinCell` is an inner-join LWW dictionary join — a key missing from
one side vanishes from the output, but both consumers need outer semantics (an item with
only one signal, a job with zero matches). There is no kernel cell that holds the latest
value per key from N map streams and emits a combined value on any change.
**Proposed shape**: a `CombineLatestCell<K, V1, V2, R>(combine: (K, V1?, V2?) -> R?)`
over `MapDelta` streams with outer-join semantics, effective-only emission, group-death
removal when all sides drop a key, `Stateful`, and late-join catch-up — the FuseCell
prototype generalized. (Relates to the G-23 OR-map deferral in 91.)

## F-2 — No threshold / bucketing operator

**Observation**: `:demo:tiering` maps a continuous fused score to a discrete tier
(S–F by fixed cutoffs); the bucketing lives inside the app's `FuseCell`.
**Why it's a gap**: score→band quantization is a recurring incremental pattern (the
attention system quantizes to bands the same way) but has no reusable dataflow form; a
naive map-over-values cell would re-emit on every score wiggle instead of only on
band change.
**Proposed shape**: a `BucketCell<K>(thresholds: SortedMap<Double, Band>)` over
`MapDelta<K, Double>` emitting `MapDelta<K, Band>` effective-only on band transitions —
i.e. hysteresis-friendly discretization as a first-class operator.

## F-3 — Keyed re-valuation over OR-sets needs app-side bookkeeping — **CLOSED**

**Closed**: promoted to kernel `KeyedSetCell` (`civictech.cell.data.KeyedSetCell`),
the keyed-upsert set source proposed below.

**Observation**: in `:demo:tiering`, an agent re-tiering an item must remove the *old*
`Valuation` element and add the new one; the OR-set can only remove an element the app
still remembers, so the app keeps an `(agent, item) → Valuation` index purely to issue
removals. Same for pairwise preferences.
**Why it's a gap**: "latest value per key, per writer" is an upsert, not a set add —
`MapCell` has upsert semantics but its `MapDelta` output cannot feed `GroupByCell`
(which consumes `SetDelta` only), so the natural encoding is blocked and every app
re-implements remove-old-then-add.
**Proposed shape**: either a keyed-upsert set source (`KeyedSetCell<K, E>` where adding
under an existing key retracts the previous element) or `GroupByCell` over map streams.

## F-5 — Edge-of-graph view composition is not glitch-free

> **Status: addressed** — `E2-ALIGN` (99-defects-engines-plan wave C1) shipped
> exactly the proposed idiom: `Use<HostManagementApi>.observeAligned` +
> `AlignedCompositeCell`, a named-inlet mirror of `WaveFrontier`'s completeness
> fold that delivers one composite snapshot per settled wave across N named
> views. See `kernel/.../cell/observe/AlignedObserve.kt`. Not yet adopted by
> `:demo:skillmatch` itself — that adoption is a follow-up, not part of this
> finding's gap.

**Observation**: `:demo:skillmatch`'s UI folds four independent outlets (matches,
match-counts, required-counts, gap) into one state snapshot. The views update
asynchronously, so a snapshot can be momentarily inconsistent — the server test
first observed a state where a match was counted (`matched: 1`) while the gap view
still listed that same skill as uncovered. Tests must await joint conditions, and
the UI can flash contradictory panels.
**Why it's a gap**: not a kernel bug — this is exactly what `GlitchFreeCell` exists
for — but there is no ergonomic way to apply glitch-freedom at the *observation
edge* (a hub folding N outlets). Each demo hand-rolls per-view folds with no wave
alignment, so the strongest consistency machinery in the kernel is unused where
apps actually read state.
**Proposed shape**: a glitch-free multi-inlet hub/collector idiom (or a
`GlitchFree`-wrapped composite view cell) that delivers wave-aligned snapshots to
the app edge; possibly just a documented recipe over the existing `GlitchFreeCell`.

## F-4 — Fixed intersect chains: topology evolution not exercised

**Observation**: `:demo:slotfinder` hard-wires three participants because adding a
participant means splicing a new `IntersectSetCell` into the chain at runtime.
**Why it's a gap**: not a missing operator — the graph-evolution machinery (GraphSpec,
promotion swap) exists — but no demo exercises *live* topology growth on a serving
pipeline. Dynamic participants would be exactly that exercise.
**Proposed shape**: a follow-up demo iteration that adds/removes participants live via
the promotion swap window, doubling as an evolution-machinery showcase.

## F-6 — No keyed-state cell with atomic cross-key (two-key) updates

**Observation**: `:demo:backlog-triage`'s `RatingCell` (`RankingCells.kt`) hosts
pairwise rating engines (elo, trueskill) whose updates are pairwise-local by
design — one game moves only the two participants' accumulators — but are
*cross-key*: updating the winner's rating needs the loser's current
accumulator in the same atomic step, which a per-key aggregator (`GroupByCell`)
can never see. The demo works around this by hosting the whole engine as one
app-level cell over the raw preference-set stream instead of a per-key kernel
aggregator.
**Why it's a gap**: the kernel's keyed aggregators (`GroupByCell`,
`MergeableGroupByCell`) assume single-key locality; there is no reusable shape
for "read/write two keys' state atomically per input event."
**Proposed shape**: a `PairKeyedStateCell<K, S>(update: (S, S) -> Pair<S, S>)` or
similar two-key-atomic aggregator over a keyed-pair event stream, so pairwise
online algorithms (elo/trueskill-shaped) can be expressed as a reusable kernel
operator instead of a bespoke app-level engine host.

## F-7 — No N-ary `MapDelta` combine

**Observation**: `:demo:backlog-triage`'s `MetaRankCell` (`RankingCells.kt`)
Borda-combines up to seven named `MapDelta<String, Double>` rating streams
(mean, elo, bt, trueskill, glicko, wenglin, wilson) by hand-folding each named
inlet into a `LinkedHashMap` and re-publishing the combined ranking via
`MapDiffPublisher` on every upstream change.
**Why it's a gap**: `CombineLatestCell` (the F-1 promotion) is binary
(`V1, V2 -> R`); there is no kernel operator that folds an arbitrary, possibly
dynamic, number of `MapDelta` streams per key.
**Proposed shape**: an N-ary `CombineLatestCell`-family operator (or a
`combineLatest(vararg sources)` / `List<Outlet<...>>`-driven variant) that
folds per-key values across N map streams with the same outer-join,
effective-only, late-join-catch-up semantics as the binary form.

## F-8 — `MetaRankCell`'s dynamically-registered inlets are invisible to descriptor generation

**Observation** (RS-4.1 finding): `MetaRankCell` registers one `FanInlet` per
source-algorithm name from a constructor-supplied `List<String>`
(`val inlets: Map<String, FanInlet<...>> = sources.associateWith { ... registerPort(name, ...) }`,
`RankingCells.kt`). `ContractProcessor.scanPorts` walks declared properties at
compile time and cannot see ports registered inside a runtime loop, so the
generated `CellDescriptor` for `MetaRankCell` lists only its one static
`outlet` property — the seven dynamic inlets are absent.
**Why it's a gap**: this is not a bug today — the G-17 spawn-time check
(`ManagedHost.spawn`, via `ContractRegistry.cellDescriptor`) is a *subset*
check (`registeredNames ⊇ descriptorNames`), so an under-complete descriptor
with extra runtime-registered ports is legal and spawns fine. It is, however,
a standing limitation: any future consumer that treats the descriptor's
`ports` list as *complete* (e.g. cold-cell enumeration without instantiation,
per `doc/ksp-dx-catalog.md` §5c) will silently miss `MetaRankCell`'s dynamic
inlets.
**Proposed shape**: none proposed — recorded as a known ceiling of
declaration-only KSP scanning (`doc/ksp-dx-catalog.md`'s "KSP is
declaration-only" constraint), not a defect to fix.

## F-9 — The `TaggedMapDelta` adapter landed; the per-issue regroup still has no operator — **PARTIALLY CLOSED**

**Partially closed**: the first bullet below — "the OR-map side has no consumer at all" — is
closed. `TaggedMapView`/`UntagCell` (96 §E1.5) landed under epic `computenet-j2x`, at
`civictech.cell.data.view.TaggedMapView` and `civictech.cell.data.op.UntagCell`, and `UntagCell`
is registered in `civictech.oracle.bind.OperatorCatalog` under the id `untag` (computenet-pez3).
A `TaggedMapDelta` is no longer a terminal in the graph. The second bullet — the per-issue
regroup — survives untouched: `GroupByCell`'s inlet is still `Serve<Propagate<SetDelta<E>>>`,
not a keyed map stream, so proposed shape (b) below is still an open gap. Verified against
origin/main d990148ae, computenet-sx0o.

**Observation**: `:demo:beadsmirror`'s ready-set derivation (computenet-98u.1.2,
`civictech.demo.beadsmirror.ready.ReadySetCell`) joins the mirror's issue-field
OR-map (`OrMapCell<MirrorKey, String>`, one key per `(issue, field)`) against
its dependency-edge set (`SetCell<MirrorEdge>`) and must maintain the result
incrementally. The join it needs is expressible in operator terms —
`edges ⋉ openIssues` on the edge's target id gives the blocked set, and
`candidates ▷ blocked` gives the ready set, which is exactly `SemiJoinCell`
twice (once matched, once `negated`). It is nevertheless hand-written as an
app-level cell, because neither input could originally reach `SemiJoinCell`:

- **The OR-map side now has a consumer: `UntagCell`.** ~~Every operator in
  `civictech.cell.data.op` takes `SetDelta` or `MapDelta`;
  `grep -rn 'Serve<Propagate<TaggedMapDelta'` over `kernel/src/main` returns
  nothing. `OrMapCell` emits `TaggedMapDelta` and the `TaggedMapView`/`UntagCell`
  adapters its own KDoc names (96 §E1.5) are not implemented, so a tagged map
  is a terminal in the graph: something can materialize it, nothing can derive
  from it.~~ That grep now returns
  `kernel/src/main/kotlin/civictech/cell/data/op/UntagCell.kt:24`'s
  `inlet: Serve<Propagate<TaggedMapDelta<K, V>>>`. `OrMapCell` emits
  `TaggedMapDelta`, and `UntagCell`/`TaggedMapView` are exactly the adapters this
  section's own KDoc named, now implemented and reachable — see "Partially closed"
  above.
- **Even untagged, the per-issue record is not reachable.** The predicate
  (`ReadyPredicate.isReady`) is a function of *all* of one issue's fields at
  once, so the composite-keyed stream must first be regrouped
  `(issue, field) -> issue -> Map<field, value>`. `GroupByCell` does exactly
  that shape of fold but consumes a `SetDelta<E>`, not a keyed map stream, so
  the regroup has no operator either. **Still true** — unaffected by the
  `UntagCell`/`TaggedMapView` landing; see "Partially closed" above.

**Why it's a gap**: the demo's most operator-shaped requirement — a two-input
incremental join with a reverse index, which `SemiJoinCell` and
`LookupJoinCell` both already implement internally — has to be rebuilt by
hand, including a private re-fold of `OrMapCell`'s own dot algebra
(`putDots`/`deadDots`, `[24-TMAP-03]` value resolution) purely to read a value
out of the delta stream the cell publishes. That re-fold is a copy of kernel
logic living in a demo, and it is the *second* consumer to need it after the
mirror's own HTTP fold. The gap remaining after this finding narrows is
entirely the second bullet: the per-issue regroup.
**Proposed shape**: two pieces, either of which unblocks composition —
~~(a) the deferred `TaggedMapView`/`UntagCell` adapter (96 §E1.5): a cell taking
`TaggedMapDelta<K, V>` and emitting the untagged `MapDelta<K, V>` of resolved
per-key values, effective-only, so the whole existing operator suite becomes
reachable from an `OrMapCell`~~ — **(a) landed**, see "Partially closed" above;
and (b) a keyed *regroup* operator over a map
stream (`MapDelta<K, V>` with `keyFn: K -> G` emitting
`MapDelta<G, Map<K, V>>`), the `MapDelta`-shaped sibling of `GroupByCell`,
which is what turns a composite-keyed entity-attribute map into per-entity
records — **still open**.

## F-10 — A hand-wired `SetCell` consumer has no way to catch up on attach

**Observation**: `ReadySetCell.derivedFrom` (same task) subscribes to
`MirrorProjector`'s two cell outlets with `outlet.subscribe(inlet)`, which
installs a consumer without firing the outlet's on-link multicast — only
`streamTo`/a negotiated handshake does — so late-join catch-up (G-22) never
runs. Replaying the baseline by hand is possible for the OR-map half
(`OrMapCell.state()` returns the accumulated `TaggedMapDelta`, real dots
included) and **impossible for the set half**: `SetCell` exposes `membership()`
but no `state()`, and a `SetDelta` synthesized from `membership()` would carry
invented tags that a later real removal could not cover, leaving the element
live forever. The demo's workaround is a documented precondition — attach the
derived cell before the projector's first record.
**Why it's a gap**: outside a `ManagedHost`, wiring two cells directly is the
ordinary in-process idiom (`outlet.subscribe(other.inlet)` appears throughout
the kernel's own tests), and in that idiom one of the two replicated data cells
can be caught up by hand and the other cannot. The asymmetry is incidental —
`SetCell` holds exactly the state a baseline read would need and serves it over
`pullServe` to a *remote* requester — not a semantic difference between the two
cells.
**Proposed shape**: a public `SetCell.state(): SetDelta<E>` mirroring
`OrMapCell.state()` (its `pullServe` reply already computes this shape for the
`since`-unfiltered case), or a documented local catch-up seam that fires the
on-link hook for a direct `subscribe` the way `streamTo` does for a routed one.

## F-11 — `IntersectSetCell` advertised borrowed input tags, so a diamond back into a union dropped live elements — **CLOSED**

**Closed**: fixed in the kernel as `computenet-vvre` (PR #324); `IntersectSetCell` no
longer republishes its inputs' tags. Recorded here rather than deleted so the *finding*
survives its fix: this is the first kernel defect the batch oracle (`:oracle`, epic
`computenet-4ru`) found on its own, and how it was found is the reusable part.

**Numbering note**: the kernel regression test for this defect
(`kernel/src/test/kotlin/civictech/cell/data/op/IntersectDiamondTagTest.kt`) cites this
finding as "F-9", the number it carried on the unmerged branch it was drafted on. F-9 here is
an unrelated `TaggedMapDelta` finding that landed first, so this entry is **F-11** — the
test's reference is stale by one entry, not a pointer at a different finding.

**Observation**: not a demo finding — `OracleSweepTest`'s BS-1 sweep
(`oracle/src/test/kotlin/civictech/oracle/run/OracleSweepTest.kt`) disagreed with the batch
reference model on 4 of 200 default-range seeds (27, 88, 154, 156) under a **single** writer.
Every failing case was a diamond: a source reconverging on one terminal through two distinct
paths, one of them an `intersect` feeding back into a `union`. Every failure had the same
shape — `StateDifference.SetDifference(onlyInExpected=[...], onlyInActual=[])`, i.e. the model
kept an element live that the kernel had already dropped. A hand-built three-event minimal
reproduction plus a passing control isolated it to the reconvergent path.
**Why it was a gap**: an operator that re-advertises a tag it merely *observed* on an input
makes that tag look like its own downstream, so a later retraction of the borrowed tag on the
other arm of a diamond covers an element the operator never actually minted — and the element
disappears while it is still live. It is invisible to any single-path topology, which is why
the operator suite's own tests did not have it: it needs reconvergence to manifest at all.
**Proposed shape** (as implemented): every fan-in operator mints its own tags rather than
borrowing its inputs'. `QuorumSetCell` got the same treatment in `978098c6`, and the general
rule is now stated as spec 21 tag hygiene (`d40e66f8`). The oracle-side consequence is the
sweep itself: full-range seed sweeps over diamond-capable topologies are what surfaces this
class of defect, so BS-1's range and config must not be narrowed to keep it green.

## F-12 — `bd`'s denormalized `is_blocked` goes stale against the live edge set

**Not a kernel gap.** This entry records an **upstream `bd` defect**, not a
missing ComputeNet mechanism. It lives here because epic `computenet-98u`
(BDS3) rule 6 designates this file as the record for exactly this class of
finding: when the ready-set differential harness
(`demo/beadsmirror/.../e2e/ReadyDifferentialHarness.kt`) disagrees with its
oracle *because the oracle is wrong*, the finding is written down and the
harness is **not** weakened to pass over it. The classification procedure — and
the list of weakenings that are forbidden — is that file's "Classifying and
recording case (c)" section.

**Observation**: two instances on **this repository's own live tracker**, both
2026-08-19, both **observed on the live tracker rather than produced by the
harness** (the harness had not yet been run against a case-(c) divergence, so
neither carries a reproducing harness seed or step index — the fields rule 6
requires of a harness-produced instance):

1. `computenet-98u.1.3` stayed out of `bd ready` (and inside `bd blocked`)
   after `computenet-98u.1.2` closed, although its only blocking edge targeted
   that now-closed bead. Its full edge set at the time:
   `computenet-98u.1[parent-child/in_progress]`,
   `computenet-98u.1.2[blocks/closed]`. Neither `bd dolt pull` nor `bd dolt
   push` cleared it. `computenet-vsbx`, which has no blocking edge at all, was
   likewise absent from `bd ready`. Primary record: the 2026-08-19 10:25Z
   comment on epic `computenet-98u` ("Scheduling finding, and it is on this
   epic's own subject").
2. `computenet-98u.2.3` — the bead this entry was written under — was omitted
   from `bd ready` at ~14:15Z while `bd dep list computenet-98u.2.3` showed
   exactly one `blocks` edge, to `computenet-98u.2.2`, whose status was
   `closed`. A `bd dolt pull` at session start did not clear it, nor did a
   no-op `bd update computenet-98u.2.3 --status=open`. The consequence was
   operational: `.claude/skills/work/scripts/next-batch.py computenet-98u.2`
   returned verdict `blocked` and the task had to be dispatched by hand.

**Why it matters**: beads persists blockedness as a denormalized `is_blocked`
column maintained across many write paths and recomputed wholesale after a
merge (`cmd/bd/sync.go` step 3 / `RecomputeBlockedAfterMerge`). Evaluating the
*live* edge set under `READY-COVERAGE.md` §2 — a blocking edge is `dep_type IN
('blocks', 'conditional-blocks')` whose target's status is neither `closed` nor
`pinned` — gives the right answer in both instances above, and the stored
column gives the wrong one. That is precisely the staleness BDS3's derived
ready set exists to eliminate: `ReadySetCell` never consumes the column,
deriving blockedness from the edge set and the blocker's mirrored status
instead. So in a divergence of this shape the **oracle** is wrong and the
derived side is right.

**Proposed shape**: nothing to build in the kernel. The handling, per epic
`computenet-98u` §2, is (a) record the instance here with its reproducing seed
and step when the harness produces one, (b) keep the failing seed pinned, (c)
leave the harness red rather than excluding the clause, retrying the comparison
or swapping the seed, and (d) leave the repair upstream — fixing `bd` is
outside this epic's scope. Consumers of `bd` inside this repository should read
readiness from the edge set (`bd dep list` plus the targets' statuses), not from
`bd ready`, whenever the distinction matters; instance 2 above is what happens
when a tool does not.

## F-13 — `FlatMapSetCell` has no seam for a mapper that can fail or must be accounted

**Observation**: `:demo:dialogue`'s extraction stage (epic `computenet-2aw`
`[AGO1-EXTR-04..08]`) is naturally a `FlatMapSetCell<Segment, ExtractedItem>`
whose mapper is an injected `Extractor` — the pipeline's determinism firewall.
But an `Extractor` may **throw** (a live model erroring, a cassette miss that
must fail loudly rather than look like an empty extraction), and the pipeline
must **record** each malformed item and each failed segment *exactly once*,
with the segment id and reason, on a status surface.

`FlatMapSetCell`'s contract forbids both: "`[f]` must be pure — dels re-apply
it to translate removals", and `catchUpOnLinked` recomputes the whole output
by re-applying `f` to the input state. So a throwing mapper faults a delta
translation that is not even the failing element's own arrival, and a mapper
that records anything double-counts on every removal, every re-admission and
every late join.

The demo's workaround is `civictech.dialogue.extract.ExtractionGate` — a
memoizing adapter that caches the delegate's outcome under the segment's
content hash, catches every `Throwable` into a cached "failed" outcome that
maps to an empty output, and guards its accounting writes by segment id so
re-application records nothing further. It is ~60 lines of adapter that exist
solely to make a fallible, effectful function look pure to the kernel.

**Why it's a gap**: "map each element through a function that can fail, keep
the successes in the derived set, and account the failures out-of-band" is a
generic incremental-dataflow need, not a dialogue-specific one — any operator
fed by an external service, a parser, or a validator has it. The kernel offers
no vocabulary for it, so every such demo must independently rediscover
memoize-by-content-key + catch-all + per-element accounting guard, and each
one gets to invent its own subtly different definition of "the same element"
(this demo keys on a SHA-256 of the segment text, deliberately ignoring the
segment's identity fields). Getting it wrong is silent: the output set still
looks right while the failure ledger inflates on every retraction.

**Proposed shape**: a `TryFlatMapSetCell<A, B>(f: (A) -> Result<Iterable<B>>)`
(or `FlatMapSetCell` gaining an `onFailure` seam) that keeps the *successful*
image in the derived set, retains each element's outcome so removals and
catch-up translate from the retained outcome instead of re-invoking `f`, and
surfaces failures on a separate outlet or a bounded read rather than through
the app's own side channel. The retention is what makes purity structural
rather than a discipline each app has to re-impose: with the outcome retained
per live input element, the mapper is invoked exactly once per element and the
"must be pure" clause becomes an internal detail instead of a caller
obligation.

**Honest limit of this entry**: the workaround has only been exercised at one
site (`:demo:dialogue`), on a single-threaded simulated host, and the gate's
cache is unbounded and non-thread-safe by the same choice `TranscriptSource`
makes. A kernel operator would additionally have to decide eviction and
concurrency, which this finding does not settle.

## F-14 — `RuleExtractor`'s relation endpoints now mint their own claims — at the cost of a third, redundant claim per "because" segment

**Observation** (computenet-xwl0, found at `computenet-2aw.3` (F3) review,
PR #635): `RuleExtractor` (`demo/dialogue/.../extract/RuleExtractor.kt`) drove
zero canonical relations. For a segment "A because B" it emitted an
`ExtractedClaim` of the *whole* trimmed segment ("A because B") and an
`ExtractedRelation` whose endpoints were the substrings "A" and "B".
`civictech.dialogue.mint.claimKey` (2aw.F3-D1) only trims/collapses
whitespace/lowercases — by design, it does not, and must not, know a
relation's endpoints are substrings of the segment that produced them — so
`claimKey("A")` and `claimKey("B")` never matched the one claim key the
segment minted (`claimKey("A because B")`). F3's pending/resolvable semijoin
split (`DialoguePipeline` stages 5d/5e) held every such candidate PENDING
forever: driven by `RuleExtractor`, the pipeline minted claims and provenance
but no relations, unless a separate utterance happened to assert "A" and "B"
as standalone claims. Every relation-bearing F3 test substituted a cassette
extractor for exactly this reason.

**Decision**: `RuleExtractor` now emits the two endpoint substrings as their
own `ExtractedClaim`s, *in addition to* the whole-segment claim (its class
KDoc's "Why the endpoint claims" carries the full reasoning). Chosen over the
bead's other two options — leaving `RuleExtractor` claim-only and shipping the
demo path on a recorded cassette, or accepting and stating a relation-free
`RuleExtractor` demo — because the epic's differentiated output is the
relations between claims, and an extractor that can never mint one
demonstrates only half the system for a heuristic explicitly meant to be
tiny and disposable (AGO3/KE1 replace it).

**Why it's a gap, not just a fix**: the trade this decision accepts is real
and is not free. A "because" segment now mints **three** canonical claims —
the whole sentence, its conclusion, its reason — where a transcript reader
would count two propositions; nobody asserted "A because B" as a standalone
claim distinct from asserting A and B. This was verified, not assumed, before
choosing it: no existing F3 test text contains "because" (so no F3 assertion
changed), the two endpoint claims carry the segment's own `utteranceId` (no
fabricated provenance), and the stance leg is unaffected (`RuleExtractor`
never emits an `ExtractedStance`). What was **not** verified — because
`DialogueApp.kt` wires no extractor yet (F4/F5 land later) — is how this
third, redundant claim reads on an actual rendered argument map, or whether
F4/F5 want to suppress/merge it once one exists. That is exactly the kind of
"results dominated by a hidden rule rather than by extraction quality" this
findings doc exists to flag (`Segmentation.kt`'s own KDoc names the same
risk for its splitting rule): the rule
producing the redundant claim is `RuleExtractor`'s "because" branch, not a
`DialoguePipeline` defect, so the fix — if F4/F5 decide it needs one — belongs
there, replacing or refining this heuristic, not in `claimKey` or the pipeline.

**Regression coverage**: `RuleExtractorTest` (`driven through DialoguePipeline
a single because-utterance mints a non-empty canonical relation set`) drives
`RuleExtractor` through `DialoguePipeline.build` end to end and asserts a
non-empty canonical relation set from one utterance alone — the discriminating
case: before this fix it stayed empty forever with no second utterance
supplying the endpoints separately.

## F-15 — CP-A3's absorb-ack is edge-local, so `emitOnFrontier` withholds output at rest whenever a wave-dropping arm is more than one hop deep

**Observation** (computenet-23bf, discovered at `computenet-2aw.3.2` (F3 T2,
RelationMint) and re-established at `915d574a9`): AGO1's relation leg splits
into a pending/resolvable pair of `SemiJoinCell`s (`DialoguePipeline` stages
5d/5e), whose two inlets both descend from the single `utterances` root. That
is exactly the "shared-source diamond" `SemiJoinCell`/`WaveGate` scope
`emitOnFrontier` to. Turning the gate on nonetheless **wedges the graph**:
with `emitOnFrontier = true` on both semijoins, 4 of `RelationMintTest`'s 5
cases fail at quiescence with an **empty** canonical relation set. (The fifth,
`REL-04`, asserts that a self-canonicalizing relation mints *nothing*, so a
wedged pipeline satisfies it vacuously — it is not evidence the gate works.)
All five pass at the shipped ungated default.

**Mechanism**: a shared root makes the diamond possible; it does not make it
sufficient. The gate's completeness condition is a **static link set** with no
upstream traversal, so each arm is an expected edge for every wave, including
waves it structurally never carries. AGO1's item-kind split creates exactly
that: a claim-only utterance is a real delta on the right arm
(`extractedItems → extractedClaims → claimKeys`) and nothing at all on the
left (`extractedItems → extractedRelations → relationCandidates →
nonSelfRelations`); a relation-only utterance is the mirror image.

The kernel already has the remedy for a structurally silent arm — CP-A3's
absorb-ack (`civictech.cell.control.absorbAck`, G-40). What this finding
establishes is that **the ack is edge-local and no plain operator relays it**.
It is minted by the absorbing operator onto its own outlet links; a
`FilterCell` / `FlatMapSetCell` hop installs no `Protocols.Progress` handler,
so an ack arriving on such a hop's inlet neither advances anything nor is
re-emitted. Only a cell that installs a frontier (`WaveGate`, `WaveFrontier`,
`CoalescingCombineCell`, `AlignedCompositeCell`) consumes one.

So the discriminator is the **depth of the silent arm**, not the disjointness
of the waves:

- absorber links **directly** into the gated inlet → the ack lands on the
  expected edge, the wave completes, the gate is correct (this is the
  pre-existing `a gated cell settles a wave one arm absorbs entirely` case);
- absorber with **one or more pure hops below it** → the ack dies at the hop.
  The wave is released only when that arm delivers a *later* wave (the
  monotone-`max` watermark advance standing in for the lost ack), so
  mid-stream output **lags**; and at rest, when the final wave is one the arm
  never carries, output is **withheld permanently**. Both arms of AGO1's
  relation leg are two hops deep, which is why the whole leg goes silent.

**Reproduction**: `FrontierGatedEmissionTest` (`:kernel`), the pair
`control - disjoint-wave arms one hop deep settle, because the absorb-ack
lands on the gated edge` (green) and `disjoint-wave arms TWO hops deep
withhold output at rest - the absorb-ack dies at the intervening hop`. Same
rig, same disjoint waves, only the hop count differs — which is what pins the
mechanism to ack non-relay rather than to the wave partition itself. The same
file's `CombineDisjointArmRig` and its own control/case pair (`control -
CombineLatestCell disjoint-wave arms one hop deep settle...` /
`CombineLatestCell disjoint-wave arms TWO hops deep emit a null-extension that
is never corrected at rest...`) repeat the measurement for `CombineLatestCell`
— see "Measured" below.

**Why it's a gap**: "derive two arms from one stream, split by element kind,
and join them" is generic incremental dataflow, not an AGO1 shape, and it is
the *normal* way to build a diamond over a heterogeneous stream. Yet it is
precisely the shape in which the flicker gate — the only remedy the kernel
offers for a non-monotone binary operator's within-wave flicker — silently
stops emitting. Silently is the sharp part: nothing throws, `bufferedWaves`
is the only signal, and the symptom (an empty derived set at quiescence)
looks like an extraction or key-canonicalization bug several stages upstream.

**Proposed shape** (not implemented here; this entry is the finding, not the
fix). Two candidates, in increasing order of ambition:

1. **Relay `Progress` through pure operator hops.** A `FilterCell` /
   `FlatMapSetCell` that receives an ack on its inlet re-emits one on its
   outlet, making the ack transitive along a chain of pure hops and reducing
   the deep case to the working shallow one. Cheap and local, but it needs a
   rule for operators with more than one inlet and for stateful hops that may
   legitimately emit later, and it makes every pure hop carry metadata-plane
   machinery it currently does not.
2. **Teach the frontier to tell a structurally silent arm from a stalled
   one** — the standing G-40/G-13 residual, identical in `WaveGate`,
   `WaveFrontier`, `CoalescingCombineCell` and `AlignedCompositeCell`. This
   is the real fix and is out of proportion to one demo.

**What ships instead, and what it costs**: `DialoguePipeline` stages 5d/5e
stay at the ungated default, with the rationale in the code. The open cost is
the transient the gate exists for — admitting the utterance that mints a
relation's last endpoint can flicker the relation into and out of the
canonical fold **within one wave**. F4's applier
(`computenet-2aw.4.2`, `[AGO1-APPLY-04]`) is the sole writer into the agora
graph and sits downstream of exactly that, so the flicker must be tolerated
rather than merely transient. It is: the applier is **pull-based** — it reads
`ObservationSink.current()` snapshots of the canonical folds after quiescence,
on an explicit `reconcile()`, and registers no `onChange` listener that writes
to agora. A within-wave admit-then-retract is therefore two folds into a View
and **zero** agora operations. That tolerance is structural, not incidental,
so it is a constraint on F4: an applier that ever becomes push-driven off the
canonical relation fold re-opens this finding.

**Honest limit of this entry**: the reproduction covers `SemiJoinCell`'s gate.
`CombineLatestCell` shares `WaveGate` verbatim and so must share the defect,
but that was not measured. Nor was the relay proposal (1) prototyped — its
cost is argued, not weighed. And the "withheld permanently at rest" claim is
about *this* graph's quiescence: a graph that keeps receiving waves on every
arm sees only the lag.

**Measured (computenet-u0oa, 2026-09-03)**: `CombineLatestCell` does share the
defect, extending `FrontierGatedEmissionTest`'s disjoint-wave-arm rig
(`CombineDisjointArmRig`) to `CombineLatestCell` — same one-hop-settles /
two-hop-withholds control/case pair, same arm shape (a kind-filtering head that
CP-A3 absorb-acks every other wave, followed by `hops - 1` pure identity hops),
gated cell and wire type (`MapDelta`) swapped and nothing else, so the green
one-hop control pins the mechanism to the same ack non-relay as `SemiJoinCell`'s.
The manifestation differs from `SemiJoinCell`'s complete silence, though, in a
way that matters: `CombineLatestCell`'s premature reconciliation (against a
still-incomplete other side) does not withhold — it **emits a wrong value**, a
null-extension the gate exists specifically to suppress, and nothing retracts
it until a later wave on the stalled arm forces a correcting flush (or forever,
at rest). That is arguably worse than `SemiJoinCell`'s case: the wire carries a
value that looks settled and is not, rather than carrying nothing.
`WaveFrontier` and `AlignedCompositeCell` were not measured; both mirror the
same static-frontier/CP-A3-non-relay shape `WaveGate` does (this entry's
Mechanism paragraph, and `WaveGate`'s own KDoc), so the same failure is
expected there too, but "shares the code" is exactly the inference this
measurement exists to not repeat on a third and fourth cell — that pair stays
an open gap.
