# 24 — Standard Data Cells, Merge Semantics, Partitioning

> **Status**: Partial (set family tagged and convergent; counters implemented incl. replicable PN form; relational operator suite + grouped aggregation + windowing-as-grouping done (M11); map/list with documented limits; partitioning, tag-epoch continuity, and restart supersession design decided in 93, unbuilt)
> **Sources**: ADR 1 (§3, §5, §14), ADR — Cellular Software Development Process (incremental dataflow layer; LASP/Differential Dataflow inspirations)
> **Implementation**: `civictech.cell.data`: `SetCell`, `UnionSetCell`, `CounterCell`, `PnCounterCell`, `MapCell`, `ListCell`, `Propagate`; M11 suite: `FlatMapSetCell`, `SemiJoinCell`, `JoinSetCell`, `GroupByCell`, `Aggregator(s)`, `Windows`, `MintedTags`; `civictech.cell.graph.RelationalGraphs` (outer joins)

## Role

Data cells are the standard library of the incremental dataflow layer:
stateful cells whose contracts are operations + delta streams, composing via
operators (union, intersect, map, …) into incrementally-maintained derived
state. They are ordinary cells — no kernel privileges.

## Established pattern (normative template)

`SetCell` is the reference shape:

```kotlin
interface SetOps<E> { fun add(element: E); fun remove(element: E) }
data class SetDelta<E>(               // observed-remove tags (G-23)
    val adds: Map<E, Set<Timestamp>>, // every add mints a unique tag
    val dels: Map<E, Set<Timestamp>>, // a remove carries the tags it observed
) : Serializable {
    fun merge(other: SetDelta<E>): SetDelta<E>  // tag-set union
}
interface SetApi<E> {
    val inlet: Use<SetOps<E>>                       // commands in
    val outlet: Subscribe<Propagate<SetDelta<E>>>   // deltas out
}
```

*(M5.2: every delta type is `@kotlinx.serialization.Serializable` with a
stable `@SerialName` (`SetDelta`, `CounterDelta`, `MapDelta`, `ListDelta`) —
deltas cross the wire as polymorphic values in the `WireFrame` envelope
(40/41), tags included, so causal merge semantics hold across processes.)*

Elements of the pattern:

1. **Command contract** on the inlet (semantic operations, not raw deltas) —
   the cell derives effective deltas from owned state.
2. **Delta contract** on the outlet; emissions are effective-only (21) —
   removing an unobserved element is a no-op.
3. **Merge on the delta type is commutative, associative, idempotent** —
   tag-set union — so membership converges regardless of arrival order.
   Add-wins is not a configured bias but a consequence: a concurrent add's
   tag is never observed by the remove. Tags are `Timestamp`s minted
   cell-locally (unique per add instance — see 22 for why wave ids are not
   reused). This is the CRDT-style ingredient for decentralized replication
   (40/42) without imposing CRDTs everywhere. (Contrast — decided in
   [93 I-4](../90-roadmap/93-feature-interactions.md): the attention
   protocol (30/34) is *not* this pattern; per-link LWW level state over a
   bounded key space — the direct downstream link set — needs no tags, and
   slot replacement + a commutative fold is its merge law.)
4. **Derived cells consume delta contracts**: `UnionSetCell` tracks live
   tags per element, forwards only new tag information (duplicate deliveries
   across diamond fan-ins dedup), and any consumer derives membership from
   the forwarded tag algebra. `CounterCell` (`increment`/`decrement` →
   `CounterDelta`) is commutative by construction: merge is addition —
   commutative but **not idempotent**, so `CounterCell` is single-instance
   (never replicated; fine for derived per-peer views). The replicable
   counter is `PnCounterCell` (session delta 4): per-source cumulative
   inc/dec totals under a private per-instance source id, `PnCounterDelta`
   merging by pointwise max — commutative, associative, idempotent — so it
   joins the set family in the mergeable class (`Replicable`, 42) and
   survives gossip-mesh echoes, partitions, and late-join catch-up.

*(G-23 resolved for the set and counter families, M4.1: convergence validated
by a 200-seed interleaving test with a control run proving arrival-order
application diverges. `MapDelta`/`ListDelta` instead carry **documented
convergence limits** — arrival-order key puts and index-addressed edits are
single-stream semantics; stable multi-writer forms wait for replication
pressure (42).)*

## Required next steps in the family

- ~~G-22: State + catch-up~~ **Resolved (M4.2)**: every data cell wires the
  post-install `onLinked` hook (13, 21) to unicast state-as-delta-from-empty
  to a late-joining subscriber, and implements `Stateful` so state survives
  drain/migrate (30/33) — no longer trapped in private fields. On-demand pull
  without relinking remains with G-18/G-13 (21).
- ~~Operator library~~ **Implemented (M4.3, extended to the full relational
  suite in M11)** — each an ordinary cell with declared incremental
  semantics, all late-join capable and `Stateful`. Correspondence to the
  differential-dataflow vocabulary: `distinct` needs no cell (SetDelta is
  set-semantic; union is the distinct-preserving merge) and `consolidate`
  is the effective-only emission rule (21); `negate` has no meaning without
  signed multiplicities (see the weighted-family deferral below).
  Convergence classes: **freely replicable** duplicates emit identical tag
  info (filter, flatMap, union), **convergent duplicates** agree on
  membership but mint distinct tags (intersect, semijoin/antijoin,
  equi-join), **single-instance** outputs are single-writer streams
  (groupBy, the counters outside the PN form):
  - `FilterCell` — predicate filter over a tagged set stream, tags intact.
  - `CountCell` — distinct-element count; emits commutative `CounterDelta`s
    on membership-size change only.
  - `IntersectSetCell` — binary (`left`/`right` inlets; n-ary by chaining);
    advertises entry tags, deletes all advertised tags on exit, absorbs tag
    churn that doesn't flip membership.
  - `JoinCell` — the **LWW dictionary join**: keyed inner join over two
    single-writer map streams where either side's put *refreshes* the pair —
    value-replacement semantics, inherently arrival-order (`MapDelta`'s
    documented limit). Useful for config/dictionary lookups; not the
    relational join.
  - `JoinSetCell` / `joinSet` / `crossProduct` (M11.5) — the **relational
    equi-join** over convergent tagged set streams: a pair is live iff both
    rows are live under matching keys; one minted tag per live pair
    ([MintedTags] — pairs re-enter when a removed row returns), emitted under
    `combine(a, b)`. Many-to-one `combine` collapses via per-pair tags (the
    output survives until its last pair dies — whole-element deletion is the
    divergent naive form, control-tested); many-to-many keys yield all
    pairs; cross product = the unit key. `combine` outputs crossing the wire
    must be `@Serializable` app types (`Pair` is not WireCodec-registered).
  - `SemiJoinCell` / `differenceSet` (M11.2) — keyed semijoin (`A ⋉ B`) and
    antijoin (`A ▷ B`, `negated`); difference (`A ⊖ B`, SQL EXCEPT DISTINCT)
    is the antijoin on identity keys. Non-monotone: re-entry rides the other
    side's removal, so output tags are **minted per entry** (`MintedTags`,
    tag hygiene, 21) — never borrowed from inputs (control test: input-tag
    reuse leaves re-entries dead under tombstone folding). Output membership
    at idle is a deterministic function of the converged add-wins input
    memberships; duplicates converge on membership, not tags; not
    glitch-free (22's wrapper is the remedy). Set semantics only — bag
    semantics (EXCEPT ALL) would need a weighted family (see below).
  - `FlatMapSetCell` / `mapSet` (M11.1) — element-wise flatMap/map over a
    tagged set stream, input tags passing through. Sound because tag algebra
    is per-(element, tag): colliding outputs **union** their preimages' tag
    sets (last-wins remapping is the divergent naive form, proven by control
    test), so an output stays live until its last live preimage dies —
    distinct-projection semantics. Transform must be pure; dels translate by
    re-applying it.
  - The shared live-tag fold lives in `TagState` (internal); `MapperCell`
    remains the scalar map/filter.
  Verified: a seeded writers→union→filter→count pipeline equals a batch
  recompute over final writer state on every seed (the prototype invariant
  for the generative harness, 52).

## Grouped aggregation (M11.3)

`GroupByCell(keyFn, aggregator)` folds a tagged set stream into per-key
aggregates on a `MapDelta<K, A>` outlet; `GroupByCell.global` is
fold-to-scalar (one constant-key group). The normative rule of the family: an
`Aggregator` is a **deterministic function of group membership** —
`value(acc)` may depend only on which elements are live, never on
insertion/retraction order. Arrival-order aggregates (first/last/scan) are
excluded by this rule; it is what makes incremental-equals-batch testable and
per-peer recompute converge.

- Membership flips (not tag churn) drive `insert`/`retract`; a group's last
  retraction removes the group (`MapDelta` removal — SQL group-death
  semantics); emission is effective-only by value equality (21). All groups
  touched by one input delta emit as one `MapDelta` under the input's wave
  id (22), so a glitch-free wrap composes normally.
- Aggregator classes: **self-inverting** (count, sumOf, avgOf — O(1)
  accumulators, retraction is arithmetic; Long selectors — float sums are
  order-sensitive) and **non-invertible** (minOf/maxOf/topK/collectToSet,
  M11.4) whose accumulator is the full support multiset (value →
  multiplicity in a `TreeMap`): needed even under set semantics because
  distinct elements can share an extracted value, and retraction of the
  current extremum must reshuffle without a re-scan. Bounded-memory top-k is
  rejected as unsound under retractions (an evicted value can become top
  again — control-tested). Selectors must be total orders with deterministic
  tie-break.
- **Windowing = key derivation (M11.6).** There is no wall clock (P1) and
  wave ids are per-source, so event time is an explicit attribute of the
  element and a window is just part of the group key: tumbling = composite
  key via `Windows.tumbling`; sliding = per-element expansion
  (`FlatMapSetCell` over `Windows.sliding`) then group. **Windows never
  close** — late elements are ordinary adds, retractions flow (view
  semantics). Deferred with triggers: watermark-driven eviction (an ordinary
  watermark-as-data source feeding upstream dels; trigger: real
  window-state memory pressure) and session windows (assignment is not a
  per-element function; trigger: first proximity-session consumer).
  Wave/tick-based windows are rejected: contents would be
  placement-dependent, breaking P1 and batch equivalence.
- **Outer joins are compositions, not cells (M11.6)**: `leftJoin` /
  `rightJoin` / `fullJoin` graph factories union the relational join's
  matched rows with null-completed antijoin rows. Eventually consistent,
  not glitch-free — transient `(a, null)`/`(a, b)` overlap while opposing
  updates are in flight; an atomic outer-join cell waits for a consumer
  that can't tolerate the transient.
- **Replication story: recompute, not gossip.** The output is single-writer
  `MapDelta` (its documented contract, satisfied by construction), so
  `GroupByCell` is not `Replicable` — and needn't be: aggregates are
  deterministic functions of convergent membership, so each peer derives its
  own from its replicated input and all converge at idle with zero
  aggregate-level coordination. Gossipable aggregate outputs (per-source
  keyed cumulative sums, `PnCounterDelta` generalized) stay deferred with
  trigger: *first aggregate-only replica under input-size pressure*.

⚠ GAP (G-44): Single-writer replication (leader→follower log-shipping)
defers its liveness half: no automatic leader election, no failure
detector, no follower-unpark rule under SAFETY_PARK, and split-brain
reconciliation beyond last-epoch-wins is undesigned. Proposal: opt-in
epoch-claim election folded from the eventually-consistent membership index
with a stated convergence/liveness bound and a generative leader-churn
harness; a failure-detection window that does not become a second heartbeat
protocol; a witness-set-superset unpark rule for SAFETY_PARK; an
application-level reconciliation hook for fenced divergent writes; an
optional ack-from-k durability tier; and per-shard leader routing when
partitions replicate (93 I-25/I-2/I-3/I-8).

## Partitioned state

ADR 1 §5: large keyed datasets shard by key for concurrency, locality, and
scale-out; non-partitioned is for atomic structures.

⚠ GAP (G-24, deferred with trigger — build when the first keyed dataset
feels placement pressure; the M4 exit app never sharded): nothing is built,
but the composite design is decided (93 I-8, evaluated under placement in
93 I-19). Everything below is decided design, not code; kernel untouched,
per P1.

A **PartitionedCell** is one composite cell — one membrane, one logical id —
owning organelle cells that each hold a **disjoint key range**. Keyed
structures only (Set = element-keyed, Map = key-keyed): positionally-indexed
`ListCell` is out of scope — a global position index across shards is
ill-defined, and `ListDelta`'s convergence limit is not dissolved by
disjointness; partition a list by keying entries on a stable id, never on
position.

- **Two membrane ports.** A routing inlet carrying the organelles' own
  command contract and a merging outlet carrying the delta contract; from
  outside the composite is indistinguishable from a single data cell.
  External links bind the composite's ports, so rebalancing never
  re-handshakes counterparts; organelles are never externally addressed.
- **Routing is a served proxy keyed by a `@Key` descriptor slot.** A `@Key`
  annotation on one data-contract parameter emits a `keyIndex` into the
  method descriptor; the router extracts the key, applies the Serializable
  total partitioner, and forwards to exactly one organelle inlet — O(1)
  dispatch, the one place per-message routing is intrinsic to the feature
  (same accepted status as `HostRoutingApi.route`). Key-less methods
  (`clear()`) broadcast and MUST be non-exclusive (KSP lint).
- **Demux preserves SPSC.** The partitioner maps each key to exactly one
  organelle, so the exclusive bit (G-21, 23) holds end-to-end: an `Owned`
  payload moves exactly once into exactly one organelle. Fan-out is not
  demux; broadcasting an exclusive payload is refused.
- **Wave-transparent merge; disjointness is the merge-safety proof.** The
  merging outlet forwards each organelle's delta preserving its
  `MessageContext` — it neither re-mints a wave nor coalesces sources; each
  organelle outlet is its own wave source. Because ranges are disjoint, a
  delta from one organelle only ever mentions its own keys: merging is
  conflict-free union, no downstream diamond joins two partitions on the
  same key, and cross-source glitches are impossible without coordination.
- **Repartition = per-range Buffering + a versioned routing table.** Moving
  range R: set R to Buffering on the router (other ranges flow), transfer
  R's state-as-delta-from-empty, flip the table atomically and bump its
  epoch, replay parked commands in order; a stale-epoch command re-routes.
  External links observe none of it.
- **Late join = per-organelle catch-up.** Each organelle unicasts its
  key-range state-as-delta-from-empty (the G-22 mechanism); the union of
  disjoint-key catch-ups IS the coherent cross-partition snapshot.

Under real placement (93 I-19) the composite reduces to shipped primitives —
partitioning must not become a second distribution mechanism, and doesn't:

- **A membrane, not a host.** The composite adds no node to the host
  hierarchy; organelles are ordinary cells spawned on ordinary hosts by
  ordinary placement (30/33, 40/42). Partitioning contributes a third map —
  keys→cells, the routing table — which is not a distribution mechanism at
  all: placement distributes cells across machines (registry), replication
  distributes copies of a cell (mesh), both untouched.
- **Routing epoch and registry location are orthogonal.** Repartition
  mutates ranges and bumps the epoch, never the registry; migration
  re-resolves the organelle's registry location, never the epoch (the table
  holds ref-bound proxies, not locations). Migration is invisible to
  routing; repartition is invisible to the registry.
- **Per-link drain suffices for single-organelle migration.** Migrating one
  organelle is the ordinary per-link drain (30/33); disjointness reduces
  the composite's ordering obligation to the per-link FIFO the drain
  already guarantees — no barrier, other partitions flow untouched.
- **Supervision is placement config the composite re-applies.** Each
  organelle is supervised by its own host; the composite holds a policy per
  partition and MUST re-apply it after every (re)placement, since
  supervision is per-host and does not migrate.
- **Replication composes per organelle.** A mergeable organelle joins its
  own gossip mesh (40/42) independently; the composite never coordinates
  replication.

⚠ GAP (G-56): PartitionedCell's adopted design (G-24, trigger armed) leaves
its distribution edges open: routing-table epoch consistency under
concurrent organelle migration, repartition-window buffering bounds,
bulk-rebalance atomicity, supervision-travels-with-placement, per-shard
replica targeting, range queries, and per-key attention routing. Proposal:
generative wire tests for the stale-epoch re-route racing registry
re-resolution and for migrate-during-repartition (ownership and placement
maps changing near-simultaneously); a buffering-bound analysis for long
state transfers under quotas and backpressure; a
supervision-follows-placement API replacing composite-local re-apply
discipline; router targeting rules when shards replicate (leader per
shard); a scatter-gather range-read protocol over the state-request
substrate; and the attention-routing proxy forwarding interest per key
(93 I-8/I-19/I-9).

## Tag continuity across epochs, restart, and swap

Three tag-algebra rules govern replication, RESTART, and instance swap.
They are decided design (93 I-14, I-22, I-27), unimplemented.

- **Tags are data, never re-minted for received state** (decided in 93 I-14
  Rule S3). A genuinely new local add mints its tag under the cell's
  current source epoch; thereafter the tag travels **verbatim** — copied
  unchanged by gossip (40/42), by `Stateful.snapshot()`, by
  `StateMigrating.importFrom` (50/53), and by state-as-delta-from-empty
  catch-up (21). A cell MUST NOT re-mint tags for state it received or
  imported. This is the replication-level complement of the landed
  operator-level tag-hygiene rule (`MintedTags` above): derived output
  mints fresh tags; relayed or imported state preserves them. Outlet-counter
  durability is optional — a fresh epoch on recovery is the always-correct
  default (`(sourceId, counter)` is never reused by construction); a
  durable host MAY persist the counter high-water purely to preserve wave
  continuity, never as a correctness dependency. The wave-side complement
  (93 I-14 Rule S4) — a `Replicable` cell's post-merge re-emission
  *originates* a fresh wave per replica, convergence riding the tags.
- **Generational supersede** (decided in 93 I-22). RESTART is decided as
  restore-the-freshest-checkpoint plus a generation-stamped `ReBaseline`
  notice over the ordinary catch-up path — never a bare local rollback. The
  consumer half lives in this file's tag algebra: on receiving
  `ReBaseline(source, supersedes, state, supersede = true)` a convergent
  consumer MUST (a) drop every live tag from the listed superseded sources
  that the re-baseline does not re-assert, (b) apply `state` by ordinary
  tag-union merge, and (c) fence the superseded source ids as dead lanes,
  rejecting any late delta stamped with a dead `sourceId`. Tags are
  source-scoped, so the retraction removes only the reverted producer's
  lost contribution — healthy peers' tags survive — and the rule composes
  with multi-writer merge; `supersede = false` (pull-merge) retracts
  nothing, forward idempotent merge only. Landed RESTART (30/31 rule 5:
  restore the spawn-time checkpoint, same sourceId with a rolled-back
  counter, no downstream reconciliation) is exactly the bare rollback this
  rule forbids (⚠ CONFLICT C-12, recorded in 30/31 and 22).
- **Swap handoff tiers are typed by merge class** (decided in 93 I-27). An
  instance swap's catch-up-fallback tier (discard the incumbent's snapshot,
  fresh source id, downstream re-baselines) is sound only for cells whose
  catch-up is idempotent against existing downstream state under a
  source-identity change: the tagged set family and complete-value scalars.
  Cells whose merge is non-idempotent across source identity — the counters
  — MUST hand off by state transform (`restore`/`importFrom`) with source
  continuity (the candidate adopts the incumbent's outlet `sourceId` +
  counter high-water): a fallback re-baseline under a fresh source would
  double-count the incumbent's already-delivered contribution (§Established
  pattern). A fallback swap MUST announce its fresh source via the I-22
  `ReBaseline` supersession notice — the landed shadow-promotion fallback
  (50/53) is silent: the candidate emits under its own fresh sourceId with
  no supersession signal. The drain-window export snapshot is the same
  `Stateful.snapshot()` that G-25 journals — one capture serves the
  handoff, the rollback checkpoint, and the journal.

⚠ GAP (G-42): Epoch source-ids and restart generations accrete unboundedly:
OR-set/PN source columns, stale glitch-free partial-wave buffers, and
frontier entries for vanished epochs are never reclaimed, and
counter/generation continuity across migration and host failure is
unpinned. Proposal: safe reclamation of provably-superseded epochs
(compaction riding G-25 checkpoints), frontier GC for orphaned partial
waves triggered by relink-driven recompute, a concrete migration-payload
field carrying the outlet counter high-water, durable-counter batching kept
off the emission hot path, and generation derivation from the journal
high-water (fresh high base on non-durable hosts) so post-restart tags
never alias (93 I-14/I-22/I-3/I-7).

⚠ GAP (G-43): RESTART's restore-freshest-checkpoint + generation-stamped
re-baseline leaves precedence and cost open: supersede vs concurrent
multi-source remove, re-baseline cost under wide fan-out, hybrid push/pull
direction, poison-write loops, and the recovery-cell pattern are
unstandardized. Proposal: state a supersede-vs-remove precedence with a
generative convergence test; bound the push-authoritative re-baseline
(diff-against-last-acked / delta-since-generation); define the per-cell
direction policy for hybrid derivation+owned-state cells; add a
poison-write escape (dead-letter the replaying write after N RESTARTs);
standardize the deadLetter→requestState recovery cell — replicated cells
re-baseline from mesh peers, resolving the RESTART-within-replication
question carried by four earlier challenges
(93 I-22/I-2/I-7/I-18/I-19/I-25).

⚠ GAP (G-49): The two-phase swap + state-transform design is by-convention
at its load-bearing spots: non-vetoing commit, contract-schema identity
across builds, source continuity under representation change, fallback
soundness, hidden-state cells, coupled-flow windows, and
rollback-after-retire. Proposal: KSP-distinguish admission policies
(Phase 0) from setup-only commit hooks; a contract-version discipline
guarding importFrom schemaVersion against same-FQN hash collisions; pin
sourceId adoption vs fresh-source reset when a candidate changes delta
representation (drain-convergence fallback otherwise); a fallback-tier
soundness marker refusing catch-up for non-idempotent cells; an explicit
non-promotable declaration for hidden-state cells; a retention window for
the retired incumbent's export snapshot with rollback-by-journal-reversal
semantics pinned against 53/24; and a transform-correctness generative
harness (93 I-11/I-27/I-21).

## Durability spectrum

ADR 1 §3 requires in-memory / durable / hybrid state.

*(G-25 resolved, M10; refined per-cell CP-C1)*: durability is a **per-cell**
concern. A host takes a `journalFor(cellRef)` selector naming the write-ahead
`Journal` each cell's accepted invocations tee to — or `null` to make that
cell **volatile** (never journaled, never replayed). The whole-host `Journal`
is the degenerate case: the constant selector returning that one journal for
every cell, byte-identical to the pre-CP-C1 tee. For a journaled cell the host
appends every accepted invocation **as a wire frame** (the same `WireCodec`
encoding that crosses the network: a journal is a bridge to disk) before
staging it; recovery rebuilds the graph, then `recoverFrom` restores the
latest checkpoint's `Stateful` snapshots and replays the frame tail through
the ordinary decode path. Because the write path is per-cell, a journal only
ever holds its own cells' records, so replaying it restores exactly those
cells and re-delivers nothing to a co-hosted volatile cell — recover each
distinct journal once. `checkpoint` is keyed the same way: it snapshots only
the cells teeing to the passed journal and compacts that journal atomically;
tombstone and PN-slot growth compact with it (`MixedDurabilityTest` proves the
per-cell scoping; its control shows a constant selector restores every cell).
Cells stay oblivious — with one honest exception:
**replay-stable identity**. A recovered instance must re-mint the identities
the network already observed, so set tags and PN source slots derive from
the cell's ref (never `randomUUID`) and the tag counter is snapshot state.
Random identity + replay = resurrected removals and double counts
(`CrashRecoveryTest` proves both directions). Remaining with a trigger:
journal segmentation/rotation and the disk-overflow mailbox (33) — the
first workload where one fsync'd file hurts.

**Boundary of the landed mechanism** (decided in 93 I-7): un-suppressed
replay through the ordinary decode path is safe exactly for the
replay-stable idempotent vocabulary above — ref-derived identities,
idempotent merges, and anti-entropy/catch-up dedup absorb the
re-emissions. For `Effectful` sinks *(G-59 resolved, W2.6, closes C-9)*: an
`Effectful` inlet journals a processed-frontier — the last applied
`(sourceId, counter)` per inlet — consulted by both `recoverFrom` replay and
post-recovery live delivery; an invocation at or behind the frontier is
suppressed-emission (dropped as already-acted) instead of re-driving the
sink. The decided journal classification still diverges from the landed
tee: 93 I-7 journals only `PORT_API` data plus topology events, while the
shipped journal appends every intake frame (management included) and does
not journal topology at all — the graph is rebuilt out-of-band before
`recoverFrom`.

⚠ GAP (G-59): The M10 journal replays intake frames, which is sound only
for deterministic, input-driven cells: wall-clock/random logic,
spontaneously-emitting sources, Effectful sinks without idempotency keys,
glitch-free partial-wave buffers, and cross-host recovery-frontier drift
are unhandled. Proposal: a determinism marker/lint forcing
non-deterministic cells to output-mode journaling (or a captured-entropy
WAL record); an emitted-delta log format for sources and a
processed-frontier shape for Effectful sinks with a generative
recovery-dedup test; document the external-idempotency ceiling as a stated
limit; verify deterministic replay reconstructs partial-wave buffers or
include them in Stateful.snapshot; and evaluate an opt-in coordinated
checkpoint for tightly-coupled subgraphs (never global, per P4) (93 I-7).

⚠ GAP (G-46): Exclusive (Owned/Leased) payloads have no defined story off
the happy path: a payload parked-but-unsnapshotted at crash is lost with no
stated at-most-once contract, and the DeadLetter envelope for
freezing/serializing/redacting them is unspecified. Proposal: state the
sender-durability contract that makes crash loss at-most-once acceptable
(or require the producing host to be durable), and pin the DeadLetter
envelope: Owned → move-by-serialize at capture, Leased → released, with a
redaction rule for non-serializable payloads — mergeable parked traffic is
already covered end-to-end by the M10 journal + anti-entropy pair
(93 I-7/I-22/I-12).

G-54 core is landed (W4.1): `BoundaryPolicy.disclosure` filters a data
cell's emitted deltas — catch-up and live uniformly — once exposed through
a `mediateOutlet()` membrane crossing (40/43, 20/21 §Pull). Residual, still
open: capability hand-out/revocation for exposed ports and taps (tearing
down *live* links, not just refusing new ones); management-plane authority
for remote graph mutation across a bridge; composition of disclosure/
integrity across nested/transitive membranes and multi-hop relays; and an
at-rest encryption stance for durable journals and parked/overflow state
(93 I-28 §8).
