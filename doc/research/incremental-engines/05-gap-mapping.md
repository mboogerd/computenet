# Gap mapping: what ComputeNet should borrow, adapt, or reject

Research date: 2026-07-23. Synthesis over docs 01-04 in this directory; the
per-fact provenance lives there. Statements here that go beyond the sourced
facts are marked *(analysis)*. Gap numbers refer to the list in
`doc/demo-findings.md` / the research brief:

1. No signed multiplicities / Z-sets / bag semantics
2. No convergent multi-writer map (OR-map)
3. No combine-latest / foreign-key lookup join / outer-join cell
4. No wave-aligned consistent multi-view snapshot at the observation edge
5. Cycles/fixpoint model unbuilt
6. Monotonic tag/tombstone growth, no compaction/GC; windows never evict
7. Glitch-freedom is single-host; no cross-replica frontier coordination

## The one structural tension to hold onto

Z-set weights (DBSP) and signed occurrence-count deltas (Naiad progress) are
**not idempotent**; shared arrangements are **explicitly single-writer**;
Materialize's internal consistency rests on **total-order timestamp assignment
at ingestion**. ComputeNet's replication admits only idempotent-mergeable
deltas over an unordered gossip mesh. So the borrowing rule is *(analysis)*:

> **Weights, arrangements, and total orders live inside a replica; tags,
> lattices, and vector frontiers cross replicas.** Every borrowed idea below
> is placed on one side of that boundary or reformulated to cross it.

This is CALM in disguise (doc 03 §5): the coordination-free zone is exactly
the monotone zone; everything non-monotone (negation, completeness,
compaction) needs a frontier — wave-scoped locally, stability-scoped globally.

## Gap 1 — Z-sets / bag semantics (doc 01 §2-3)

**Borrow**: Z-sets as the *operator-internal* representation for a weighted
cell family. DBSP gives, for free once weights exist: EXCEPT ALL and bag
semantics; `dist` with O(|Δ|) incremental cost; the delta-join form
`Δa×Δb + a×Δb + Δa×b`; group-by-as-indexing that is linear hence incremental;
and the LTI-vs-bilinear taxonomy that predicts which cells carry state.

**Adaptation forced** *(analysis)*: a `WeightedSetDelta` (element → signed
count) merges by addition — commutative/associative but **not idempotent**, so
it must be classified like `CounterCell`: single-instance, non-`Replicable`.
Two boundary adapters make it useful anyway:

- *tags → weights*: |live tags| is a weight; a tagged `SetDelta` maps to a
  weighted delta (adds = +1 per new live element, dels = −1 per killed one).
  ComputeNet's effective-only rule already computes exactly this sign change —
  it is DBSP's H function in disguise.
- *weights → tags*: crossing back requires minting tags (as `JoinSetCell`
  already does) — i.e. a `dist`-like boundary cell that renders a weighted
  stream as an OR-set stream.

The PN-counter shows the known trick for making counts replicable (per-source
cumulative totals, pointwise max) — a per-source-weights variant
(`sourceId → element → cumulative +/-`) would be the gossipable version of a
bag, at vector-width cost per element. Whether that cost is acceptable is an
open design question (see Open questions).

**Reject**: replicating raw signed weights over the mesh; double-delivery
double-counts (same failure mode already documented for `CounterCell`).

## Gap 2 — Convergent multi-writer OR-map (doc 03 §2, §4)

**Borrow**: the Riak-map / delta-ORMap design wholesale — it is the settled
answer, with its failure modes already documented by its authors:

- One **shared causal context** for the whole map, never reset (per-key
  contexts provably re-admit stale values on key re-creation).
- **Reset-remove** semantics: field remove = remove all *observed* content;
  concurrent updates survive with only the concurrent part.
- **Deferred context ops** for removes that arrive ahead of the state they
  reference — ComputeNet's park/replay machinery is the natural home
  *(analysis)*.
- Restrict embedded values to the idempotent-mergeable (`Replicable`) class —
  Riak's embedded-counter anomaly is the documented consequence of ignoring
  this.
- Dedupe actor/clock metadata at the codec layer from day one — Riak names
  metadata bloat as "a serious issue."

ComputeNet is closer than it looks *(analysis)*: `KeyedSetCell` already does
per-key observed-remove with atomic retract+add; the OR-map generalizes its
per-key memory from "one element" to "one embedded mergeable value," and the
delta-AWSet shows removal shipping as context-only (no tombstone payload).
This closes G-23 and unblocks combine-latest/FK-join replication.

## Gap 3 — Combine-latest / FK lookup join / outer join (doc 02 §6, doc 01 §3)

**Borrow**:

- **Delta-join composition** (Materialize dogsdogsdogs): compose n-way joins
  as n per-input delta pipelines over shared *input* views instead of chained
  binary `JoinSetCell`s — zero intermediate minted-tag sets; state shifts to
  per-input indexes that are shareable across queries. The FK lookup join
  (backlog `02`) is the n=2 case where one side is an index *(analysis)*.
- **DBSP's bilinear form** as the per-cell contract: any new binary operator
  should be written as `Δa⋈Δb + a⋈Δb + Δa⋈b` against integrated inputs — this
  is the correctness template for `LookupJoinCell` and `CombineLatestCell`.
- **Outer join = join ∪ frontier-gated antijoin** *(analysis, CALM-informed)*:
  the null-extension side is non-monotone (asserts absence), which is *why*
  the current composition is only eventually consistent and emits
  later-retracted nulls (the internal-consistency essay's exact outer-join
  failure, doc 04 §3). Fix is not a smarter cell but gating the antijoin's
  emission on a wave frontier (gap 4 machinery) — locally glitch-free outer
  joins fall out of the same investment.

**Reject**: trying to make outer-join emission convergent *without* a frontier
— CALM says absence-based emission is non-monotone, so some sealing is
unavoidable; per-wave sealing is the cheapest ComputeNet already has.

## Gap 4 — Consistent multi-view snapshot (docs 01 §6, 02 §7, 04 §3-4)

**Borrow**:

- The *goal spec* from Jamie Brandon: **internal consistency** — "every output
  is the correct output for some subset of the inputs provided so far." The
  balanced-transfer test suite is directly reusable as ComputeNet's acceptance
  benchmark for the observation edge.
- The *mechanism shape* from DD/Materialize: determinism + explicit timestamps
  ⇒ independently computed views align at the same frontier. ComputeNet
  already has deterministic delta application and per-source waves; what the
  observation edge needs is to read all views **at the same per-source vector
  frontier** — the multi-input dual of `GlitchFree` (backlog F-5), which is a
  local, coordination-free construction *(analysis)*.
- Feldera's batch-prefix phrasing is the right guarantee statement: "view
  state corresponds to what a batch system would produce on *this replica's
  per-source prefix*."

**Reject**: Materialize's scalar virtual time / timestamp oracle — a global
total order requires a coordination point at ingestion. The coordination-free
analog is a **vector frontier** (per-source prefixes), which cannot totally
order concurrent updates from different sources — accept that; it is exactly
what convergence already means in ComputeNet.

## Gap 5 — Cycles and incremental fixpoints (docs 01 §4, 02 §1-2)

**Borrow**, as one coherent package:

- **Naiad's structural recipe**: cycles pass through ingress/feedback/egress
  vertices that mechanically append/increment/strip a loop counter — the
  cycle-head cell (backlog `agora-dynamic-cycle-head-admission`) should be
  this triple, making the wave id `(outer wave, iteration)` inside a cycle.
- **DBSP's semantics**: the cycle rule proves the incremental version of a
  feedback loop is the loop around the incrementalized body; semi-naive
  evaluation falls out as a corollary; termination = the differentiated
  change stream becomes empty — which ComputeNet's **effective-only emission
  already detects**: a cycle terminates when a full iteration produces only
  empty deltas *(analysis)*. Guarantee scope carried from the paper:
  stratified queries over finite domains.
- Two-dimensional time `(t0, t1)` for *incremental* recursion (input update →
  stream of fixpoint adjustments) maps to (source wave, iteration counter).

This is the highest-leverage borrow for Agora *(analysis)*: argumentation
semantics (grounded extensions, transitive support/attack) are fixpoint
computations, currently inexpressible.

## Gap 6 — Compaction, GC, eviction (docs 01 §5, 02 §4, 03 §2-3, 04 §1-2)

Three complementary mechanisms, for three kinds of state:

- **Event-time state (windows): Feldera's lateness → waterline.** Per-inlet
  lateness annotation; waterline = monotone max-minus-lateness computed *in
  the dataflow itself* (z⁻¹-delayed max), riding ComputeNet's per-source
  waves with no coordination. Carries Flink's design decision: eviction is
  destructive — a late add after eviction must be dropped, side-channeled, or
  trigger re-baseline; Feldera's refuted "pure optimization" claim (0-3) is
  the warning that this is a semantic choice, not an optimization.
- **Operator-internal history: reader-frontier compaction** (shared
  arrangements): coalesce updates at times indistinguishable to every
  downstream reader (`rep_F(t)`), with proven correctness/optimality. Maps to
  ComputeNet's consumer-side views and join state, using downstream wave
  acknowledgement as the reader frontier *(analysis)*.
- **Replicated CRDT state (tags/tombstones): causal stability.** The correct
  trigger for discarding del-tags: a tag is discardable once no concurrent
  operation can still arrive (per-node, oracle = per-peer delivered
  watermarks — PN-counter-shaped, gossipable). Then the delta-AWSet trick
  removes tombstone payloads from the wire entirely (removal = context-only),
  and stable state "degenerates losslessly into a sequential data structure
  plus a small unstable frontier." Costs to accept: membership knowledge
  (LocationRegistry must be trusted as complete for the stability read) and
  the idle-replica liveness problem — a silent replica freezes stability for
  everyone; Bauwens' ack/heartbeat scheme is the known fix. CALM's blunt
  summary: "even tombstone GC ultimately needs agreement" — the agreement can
  be lazy and off the critical path, but it cannot be zero.

## Gap 7 — Cross-replica frontier coordination (doc 02 §2-3)

**Borrow**: Naiad's distributed progress protocol — the *only* verified design
in this space that needs no consensus: FIFO broadcast of signed
occurrence-count deltas, safety property "no local frontier ever moves ahead
of the global frontier," mechanically verified (TLA+, Isabelle/HOL).

**Adaptation forced** *(analysis)*: the signed-delta accumulator is not
idempotent and assumes fixed membership + reliable FIFO. Two options:

1. Run it as-is over ComputeNet's per-source-counter links (which already
   give per-pair FIFO/exactly-once) — works, but inherits fixed-membership
   fragility.
2. Reformulate as a **join-semilattice**: each replica gossips a per-source
   low-watermark map, merged by pointwise max — idempotent, redelivery-proof,
   membership-tolerant, and the same shape as the PN-counter ComputeNet
   already replicates. The lost precision: watermarks advance monotonically
   only (no retraction of progress), and liveness degrades when a replica
   departs without closing its frontier — the same failure mode as causal
   stability (gap 6), suggesting one shared membership/heartbeat substrate
   serves both.

Note the layering *(analysis)*: gap 7's cross-replica frontier and gap 6's
causal stability are the **same primitive at two freshness levels** — "all
replicas have delivered past τ" (stability) is the terminal state of "how far
has each replica delivered" (frontier). Build the per-peer delivered-watermark
exchange once; read it at wave granularity for JoinBarrier-style coordination
(G-39) and at stability granularity for GC.

## Ideas that do NOT transfer (consolidated)

| Idea | Why not | Verified basis |
|---|---|---|
| Raw Z-set weights on the wire | Addition not idempotent; gossip redelivery double-counts | doc 01 §2 |
| Shared arrangements as replicated indexes | "Do not support multiple writers" — authors' own invariant | doc 02 §5 |
| Materialize scalar virtual time / timestamp oracle | Total order assigned at ingestion = a coordination point; ComputeNet refuses exactly this | doc 04 §4 |
| Feldera-style global strong consistency | Batch-prefix guarantee of *one* totally ordered pipeline; per-replica per-source-prefix is the ceiling | doc 01 §6 |
| LVars threshold reads | Need predictable monotone query values; OR-set tags are fresh/unpredictable, query nonmonotonic | doc 03 §1 |
| Bloom^L seal-based negation | Sealing freezes values; Lasp's causal-metadata retraction (ComputeNet's model) supersedes it | doc 03 §1 |
| Naiad progress protocol verbatim | Fixed membership, reliable FIFO, non-idempotent accumulator | doc 02 §3 |

## Open questions (carried from research, unresolved)

1. Tag-count ↔ weight boundary: can per-replica DBSP-style circuits consume/
   emit OR-set tag deltas without double-counting under gossip redelivery —
   and is the per-source-cumulative-weights bag (PN-counter generalization)
   affordable per element?
2. Semilattice reformulation of Naiad progress: exactly what liveness is lost
   when a replica departs without closing its frontier, and is
   lease-based eviction from the membership view sufficient?
3. Is causal stability the correct `rep_F(t)` analog for OR-set tag
   coalescing, and can it reuse `LocationRegistry` membership, or does it
   need a stronger (complete, lease-fenced) membership view?
4. Does the OR-map fall out of `KeyedSetCell` + shared causal context, or is
   full dot-store/causal-context factoring (replacing per-element tag sets)
   the prerequisite?

## Suggested priority *(analysis)*

1. **OR-map** (gap 2) — settled design, unblocks three backlog items
   (combine-latest, FK join, replicated map features), moderate scope.
2. **Vector-frontier observation edge** (gap 4 + outer-join gating of gap 3)
   — local-only, no replication changes, immediately visible in demos; adopt
   the balanced-transfer benchmark.
3. **Per-peer delivered-watermark substrate** (gaps 6+7 together) — one
   primitive, two consumers (JoinBarrier coordination, causal-stability GC).
4. **Lateness/waterline eviction** (gap 6, window state) — independent,
   well-specified, rides existing waves.
5. **Cycle package** (gap 5) — biggest semantic payoff (Agora fixpoints), but
   deepest kernel change; DBSP+Naiad give the full blueprint when ready.
6. **Weighted cell family** (gap 1) — do last; the tag↔weight boundary
   question should be settled by the OR-map and GC work first.
