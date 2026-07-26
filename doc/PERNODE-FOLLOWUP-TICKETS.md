# Per-node composition — follow-up tickets

Follow-ups from the per-node run's honest residuals ([CHANGELOG-pernode.md](CHANGELOG-pernode.md)
§Known limitations). Baseline: `main` @ `0a40faa` (whole per-node run merged +
changelog). House style: make the cited code true; name the test up front;
100-seed generative where applicable; **controls that must diverge**; no behavior
change for non-opting graphs. Each ticket is self-contained — a fresh agent should
be able to start from it without reading the run history.

---

## FU-1 — Partial-interest pull crosses the wire — P2 · Low/Medium · `repl`+`wire`

**Context**: FRESH · **After**: — · **Files**: `kernel/.../cell/port/StateRequestProtocol.kt`,
`kernel/.../cell/data/PartitionedCell.kt` (`PartitionedShardSet.pull`, `ShardCell`
`StateRequest` handler), test.

**Problem.** A scatter-gather pull (`PartitionedShardSet.pull`, PN-5) fans a
`StateRequest` to every interest-*overlapping* shard and each replies `baselineTo`
with its slice. But `StateRequest.scope` is marked `@kotlinx.serialization.Transient`
(`StateRequestProtocol.kt:50`), so the requester's sub-slice interest does **not**
cross a real bridge: a cross-host shard receives `scope == null ⇒ Interest.Total`
and replies with its **entire** slice rather than the intersection with the
requester's interest. It is correct (the requester still filters), just not
*narrowed* — a cross-host pull over-fetches. In-process pulls narrow fully because
the `Interest` object is passed by reference.

The `@Transient` was inherited from PN-5, which landed *before* PN-6 made the whole
`Interest` algebra kotlinx-serializable. **That blocker is already gone**: every
`Interest` arm is `@kotlinx.serialization.Serializable` and registered as a
polymorphic subclass in `WireCodec` (`WireCodec.kt:181-188`), and
`InstanceSet.Assignment.interest` already rides the wire as `@Polymorphic Interest`.
So this is now a small change, not a design problem.

**Implement.**
- Change `StateRequest.scope` from `@Transient` to a wire-carried field, mirroring
  `Assignment.interest`: annotate it `@kotlinx.serialization.Polymorphic val scope: Interest? = null`
  (keep the `null ⇒ Total ⇒ verbatim` default so every existing 2-arg caller and
  the whole-state reply are byte-identical). Confirm `WireCodec`'s polymorphic
  `Interest` module already covers it (it should — same channel as `Assignment`).
- In `ShardCell`'s `StateRequest` handler, narrow the reply to
  `shardInterest ∩ request.scope` (use `Interest.Intersect`/`overlaps`/`admits`
  from β's algebra) instead of the shard's whole slice.
- Keep `PartitionedShardSet.pull` fanning only to overlapping shards (unchanged);
  the new part is that each shard now further narrows its reply to the requested
  sub-slice.

**Test.** `PartitionedPullScopeWireTest` (or extend `PartitionedPullTest`): a
requester pulls a 3-shard cell **over real `Peering.loopback` bridges** with a
*partial* scope that intersects only part of each shard's range; assert the reply
each shard sends contains **only** keys in `shardInterest ∩ scope` — i.e. the
cross-host reply is genuinely narrowed, not the whole slice then filtered. 100
seeds. **Controls**: (a) `scope` reverted to `@Transient` ⇒ the cross-host reply
carries keys outside the requested sub-slice (today's over-fetch, observable by
asserting reply size / key set); (b) `scope = null` (Total) ⇒ byte-identical to
today's whole-slice reply.

**Done when.** Named test green, both controls diverge, full `./gradlew test`
green, and the changelog's "Partial-interest pull does not cross the wire" line can
be deleted.

---

## FU-2 — Converged-membership barrier: close the unknown-joiner premature-release race — P1 · Medium · `repl`+`consistency`

**Context**: FRESH · **After**: — · **Files**: `kernel/.../cell/replication/Replication.kt`
(`replicaFrontier`, ~:138), `kernel/.../cell/host/LocationRegistry.kt`
(`instancesOf` / membership announcement), possibly `WatermarkCell`, test.

**Problem — this is a genuine (narrow) correctness gap, not just a scoping note.**
`Replication.replicaFrontier(logicalId, creationFence, degrade)` gates a glitch-free
release on the *covering quorum* — the members whose interest admits the key
(`Replication.kt:138-170`). The creation fence (R13) correctly turns a member that
is **known** to the local view but has **not yet published a watermark row** into a
conservative hold (a rowless known member reads as bottom and holds the wave). But
the quorum is computed over `registry.instancesOf(logicalId)`, which is
**eventually consistent**: a covering member the local view has **not learned about
at all** is simply absent from the quorum, so the wave can release *before* that
member's data for the key arrives. For a consumer using `useReplicaFrontier`, that
is a **premature release → a torn / non-glitch-free read** for that key, in the
window between a new covering instance joining and its existence gossiping to this
node.

PN-7 documented this and PN-19 closed the *recoverable-stall* half (DEGRADE drops a
*known, suspended* member and restores it). The *unknown-joiner* half is still
open: you cannot wait on a member you do not know exists. Impact is bounded (only
during membership convergence, only for graphs opting into the covering-quorum
replica frontier), but it is a real glitch-freedom violation, so it should be
closed rather than left documented.

**Design direction (for the implementing agent to firm up).** The fix is a
*converged-membership barrier*: the settling node must be able to tell "I know the
full covering set for this key range" from "I might not." Candidate mechanisms to
evaluate:
- A per-logical-id **membership epoch / version** on the instance-set announcement
  (the `InstanceSet` lattice, `replication/InstanceSet.kt`, is the natural home —
  it already gossips per-instance `(interest, epoch)` as a `Replicable` max-register).
  A wave for a key can only *release* once the node has observed a membership
  version at least as new as the one under which the key's covering set was last
  changed — otherwise it holds (conservative), never releases early.
- Alternatively a coverage-completeness watermark: an instance announces "I cover
  range R at epoch E" and a settling node refuses to release a key in R until it
  has a coverage announcement whose epoch dominates the routing epoch the wave was
  produced under.

Either way the invariant is: **an unannounced covering member must cause a
conservative hold, never a premature release** — the same asymmetry the creation
fence already gives for known-rowless members, extended to unknown members. Prefer
reusing the `InstanceSet` epoch lattice + the delivered-watermark companion over a
new protocol.

**Test.** `UnknownJoinerFenceTest` — a covering instance joins an instance set
*mid-run* and writes to a key range while its existence is still gossiping to the
settling node (deterministically reproduce the announcement lag, as
`ShardedReplicaFrontierTest`/`MemberDepartureFrontierTest` pin membership
snapshots). Assert the DEGRADE/WAIT board **never surfaces an uncovered value** for
the joining member's key range across the convergence window. 100 seeds.
**Controls**: (a) the barrier off (today) ⇒ premature release for the joiner's key
on some seed (the bug, executable); (b) a fully-converged membership ⇒ byte-identical
to today (no extra holding once everyone is known).

**Done when.** Named test green, both controls diverge, full gate green, and the
changelog's "Membership convergence race remains for entirely-unknown joiners" line
can be deleted (or narrowed to whatever genuinely remains, e.g. Byzantine cases).

**Watch.** Keep the default (empty `originKeys`, no covering quorum) byte-identical;
this barrier only affects graphs that opted into `useReplicaFrontier`. Do not
regress liveness — the barrier must still *release* once membership converges, not
hold forever (an over-conservative barrier that never releases is control (a)'s
opposite failure and must also be guarded).

---

## FU-3 — Partitioned rolling promotion: end-to-end test coverage — P2 · Low · `evolve`+`data`

**Context**: FRESH · **After**: — · **Files**: kernel **test only**
(`kernel/.../cell/replication/` or `evolve/`), plus tiny additive touch-ups to
`evolve/Evolution.kt` / `replication/Replication.kt` (`rebind`) *only if* a shard
exposes a gap.

**Problem.** PN-14 shipped rolling promotion for a **replicated** node
(`Promotion.promoteReplica` + `Replication.rebind`, reuse-ref = crash-recovery-
equivalent) with `ReplicatedPromotionTest`. The ticket also specified the *same
rolling form shard-by-shard for a **partitioned** node* (the G-50 residual). That
path is **documented and structurally supported** — `promoteReplica`/`rebind` take
`Replicable<*>` and `ShardCell` is `Replicable`, so a shard is accepted by the same
code — but it has **no test**. "Documented + structurally accepted" is not
"proven"; a partitioned promotion could tear on repartition-vs-promotion races,
shard-router rebind, or per-shard frontier identity in ways the replicated test
never exercises.

**Implement.** Primarily a test. Roll a promotion **shard by shard** across a
partitioned node (`PartitionedShardSet` of `ShardCell`s): shadow → judge → promote
one shard's implementation while the others serve and writers continue; then roll
to the next shard. If the run surfaces a real gap (e.g. the router's routing table
or the delivered-watermark companion doesn't follow a shard's reused ref through
`rebind`, or a repartition racing an in-flight promotion loses a slice), fix it
minimally in `Evolution`/`Replication`/`PartitionedCell` and note it — but expect
the bulk to be test.

**Test.** `PartitionedPromotionTest` — a 3-shard partitioned node (over real
bridges); shadow→judge→promote shard 0's cell while shards 1–2 serve and writers
write; roll to shard 1; interleave one repartition. 100 seeds: board converges to
the batch group-by of all writes; a `useReplicaFrontier`/settlement-gated consumer
never surfaces an undelivered element across the swaps; memberships stay pairwise
disjoint (no double-count). **Controls**: (a) candidate spawned with a **fresh
CellRef** instead of reusing the incumbent shard's ref ⇒ the shard's routing-table
entry / watermark row orphans and post-promotion writes to that shard's range are
lost or double-counted; (b) promotion racing a repartition **without** re-running
link-time authority (rebind) ⇒ a moved key's slice forks.

**Done when.** `PartitionedPromotionTest` green, both controls diverge,
`ReplicatedPromotionTest` still green unchanged, full gate green, and the
changelog's "Partitioned rolling promotion is documented but only replicated is
tested" line can be deleted.

---

## FU-4 — Adapter synthesis: an `Adapt` arm for nature reconciliation — P2 · High · `link`+`gen` — **EXPLORATORY / COLLABORATIVE**

**Context**: FRESH · **After**: — · **This ticket is a design exploration, not a
prescriptive build.** It should produce a design proposal (and a spike) for review
*before* committing to an implementation. Bring questions back; do not silently
pick a design.

**Background (what exists today).** Nature reconciliation (`port/NatureNegotiation.kt`,
CP-F1..F3) is deliberately binary: `reconcile(offered, required)` returns **only**
`Reconciliation.Direct` (compose) or `Reconciliation.Refuse(NatureMismatch)` (a
loud, typed refusal). There is **no `Adapt` arm, no planner, no auto-insertion** —
by design: the original plan gated adapter synthesis on post-evidence proof that
people were hand-stacking the *same* adapter repeatedly, and the composition run
found none, so it stayed out. A refused link today is a compile-/link-time error
the developer fixes by wiring an explicit adapter cell themselves.

The refusing axes today are `OWNERSHIP`, `MERGE_IDEMPOTENCE`, `MONOTONICITY`,
`WAVE_PARTICIPATION`, `INSTANCE_SCOPING` (`LINK_FLOW_AXES`); structural natures
(the `CellManifest`: GLITCH_FREE/DURABLE/… ) deliberately never refuse. Levels are
ranked per axis; an axis refuses iff `offered.rank < required.rank`.

**The question to explore.** When an outlet's nature is *below* what an inlet
requires on some axis, could the framework **synthesize (or select) an adapter
cell** that lifts the producer to the required level, instead of refusing — turning
`reconcile` into `Direct | Adapt(cell) | Refuse`? Concretely:
- **`WAVE_PARTICIPATION`** (UNWAVED → WAVED): could an unwaved producer into an
  ALIGN inlet get a synthesized "waver" that assigns wave ids, rather than a
  refusal? (This is the exact silent-drop PN-12 turned into a refusal.)
- **`INSTANCE_SCOPING`** (SINGLETON → INTEREST_SCOPED): could a non-`Scoped` delta
  get a wrapping that makes it interest-sliceable?
- **`MERGE_IDEMPOTENCE`**: almost certainly *not* adaptable (you cannot make a
  counted accumulator idempotent without changing its semantics) — a good example
  of an axis where refusal must remain. Part of the exploration is deciding **which
  axes are adaptable and which must stay hard refusals.**

**Design space / open questions to resolve with the reviewer.**
1. **Registry vs synthesis.** Is an `Adapt` a *lookup* in a registry of
   developer-registered adapters keyed by `(axis, fromLevel, toLevel)`, or genuine
   code synthesis? A registry is far lazier and safer; synthesis is the ambitious
   version. Recommend starting with the registry framing.
2. **Which axes are adaptable?** Per-axis: is there a semantics-preserving lift?
   (WAVE_PARTICIPATION plausibly yes; MERGE_IDEMPOTENCE no.) Produce the table.
3. **Composition & determinism.** If two axes both need adapting, is the adapter
   order well-defined? Does an inserted adapter cell get a stable, replay-safe
   `CellRef` (it must, given PN-1 — everything derives from the ref)? Where does it
   live (host placement)?
4. **Opt-in vs automatic.** Given the plan's caution against auto-insertion, should
   `Adapt` be *offered* (the reconcile result names an available adapter, the DSL
   decides whether to insert) rather than silently inserted? Recommend: reconcile
   returns `Adapt(candidate)`; insertion is an explicit DSL/handshake choice, never
   silent — preserving "no silent bridging."
5. **Failure mode.** What happens when an adapter exists but its own natures don't
   satisfy the inlet (adapter chain)? Bounded search or single-hop only?
6. **Does the evidence justify it?** The run found no repeated manual adapter
   stacks. Re-check: are there real graphs (in `demo/**` or planned) that today
   force a hand-written waver/scoper at a link, i.e. genuine demand? If demand is
   still absent, the honest outcome may be "keep it refuse-only, revisit when N
   real stacks appear" — that is a legitimate exit.

**Deliverable (phase 1 — before any production code).** A short design doc
(append to `doc/spec/…` or a new `doc/adr/`) that: (a) states the adaptable-axis
table with rationale; (b) picks registry-vs-synthesis and opt-in-vs-automatic with
reasoning; (c) sketches the `Reconciliation.Adapt` shape and where insertion
happens; (d) gives a throwaway spike proving one axis end-to-end (suggest
`WAVE_PARTICIPATION`: a registered waver adapter selected by reconcile, inserted by
the DSL, letting an unwaved producer feed an ALIGN inlet *with* wave ids so the
join settles); (e) an explicit go/no-go recommendation. **Stop there and review**
before building the general mechanism.

**Test (for the phase-1 spike).** `AdaptWaveParticipationSpikeTest` — an unwaved
producer wired to an ALIGN (glitch-free) inlet: today `reconcile` → `Refuse` and
(bypassing the refusal) the frontier silently drops (`unmatchedDrops ≥ 1`, PN-0a).
With the spike's registered waver adapter selected via `Adapt` and inserted, the
inlet receives waved emissions and the join settles. **Control**: `MERGE_IDEMPOTENCE`
must still `Refuse` (no adapter is registered / offered for it) — proving adaptation
is scoped to axes with a semantics-preserving lift, not a blanket "never refuse."

**Explicitly NOT in scope for phase 1.** General multi-axis adapter chains,
synthesis (as opposed to registry lookup), and automatic silent insertion. Those
are follow-ons gated on the phase-1 review.
