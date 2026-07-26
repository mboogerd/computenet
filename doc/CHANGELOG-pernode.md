# Changelog — Per-node composition

All changes below are merged to `main` (@ `96512e7`, gate green: `./gradlew test` BUILD SUCCESSFUL, 49/49 tasks). This release makes a single cell (a "node") composable along every axis at once — a cell can now be durable **and** replicated **and** partitioned **and** glitch-free at the same time, and the framework detects the combinations that would silently corrupt data and refuses them at wiring/formation time instead. Package is `civictech.cell.*` throughout.

Grouped by what you can do now, not by ticket.

---

## Aggregates you can replicate and shard

**What's new.** Group-by aggregation used to be single-writer only (`GroupByCell` recomputes from tags and is explicitly *not* replicable — last-writer-wins would lose concurrent partial sums). You can now build a group-by that converges across replicas by folding partial results with a merge operator, and its output delta can be sliced to a partial-interest peer so an aggregate can be *sharded*, not just replicated whole.

**How to use it.** `MergeableGroupByCell(keyOf, accumulate, merge)` — declares `Replicable`, so its ports automatically carry the idempotence nature (a non-idempotent accumulator wired to a replicated sink is refused at link time, see below).

```kotlin
// region -> running max price, convergent across a 3-replica mesh
val board = MergeableGroupByCell<Trade, Region, Int>(
    keyOf = { it.region },
    accumulate = { it.price },
    merge = { a, b -> maxOf(a, b) },   // commutative + associative + idempotent
)
```

Use a commutative/associative/**idempotent** merge (`max`, `min`, set-union) when the same key can be delivered to more than one replica; a counted `+` is only safe across strictly disjoint keys. `GroupByCell` is unchanged and remains the right default for non-replicated use. `MapDelta` now also implements `Scoped`, so `Replication.scopeToInterest` delivers only the admitted keys to a partial-interest peer.

## Partitioned nodes are now first-class cells

**What's new.** A shard used to be a write-only sink with no outlet — you couldn't make a partitioned node also durable, replicated, or pull-served. Now `ShardCell` is a full dataflow cell (`Cell, Stateful, Replicable`): it has an outlet, a delta inlet, a `StateRequest` handler, and a `snapshot()` of `(state, interest, epoch)`. A partitioned node survives a crash (its shed range doesn't resurrect on recovery, because interest is snapshotted state, not a constructor arg), and a partitioned logical id can be *pulled* from.

**How to use it.** `PartitionedShardSet` routes writes to shards and, new in this release, answers a pull by scatter-gather across the shards that overlap the requester's interest — each shard replies with its own slice over the real transport (in-process or across a bridge), and the requester assembles the union:

```kotlin
shardSet.pull(replyTo = consumer.inletRef, scope = Interest.Total) { shardRef -> retained[shardRef] }
```

A second, incremental pull returns only tags you haven't seen — the consumer keeps a **per-shard** `since` (see the interest algebra below), so non-contiguous shard holdings never lose tags to a merged scalar. Freshness contract: each shard's reply is internally consistent; across shards it is arbitrary (a pull is a baseline, never a wave).

## An honest, serializable interest algebra

**What's new.** Interest (which keys a replica/shard holds) used to be anonymous non-serializable predicates that lied about overlap. It's now a closed, wire-safe algebra with honest `overlaps`/`admits`.

**How to use it.** `civictech.cell.replication.Interest`: `Total`, `Empty`, `Slots(slots, totalSlots)` (hash partitioner, any key), `Ranges(listOf(Range(lo, hi)))` (ordered numeric partitioner), and the combinators `Union`, `Intersect`, `Complement`. All are `@Serializable` and cross the wire.

```kotlin
val westHalf = Interest.Slots(setOf(0, 1), totalSlots = 4)
if (westHalf.overlaps(otherShard.interest)) { /* they share keys */ }
```

## Wave identity and recovery that actually survive a restart

**What's new.** Port identity used to be a fresh random UUID per process, so a wave's `sourcePort` was ephemeral and replayed journal frames were silently dropped or stalled asymmetric joins on recovery. Now:
- **Port refs are replay-stable** — derived deterministically from `(cellRef, portName)`, so rebuilding the same graph mints the same port refs and wave edge-matching survives a restart. No API change; hosted ports get stable refs automatically.
- **Journal replay is a baseline, not a live wave** — during `ManagedHost.recoverFrom`, replayed emissions are stamped as a baseline, so a durable cell can feed one arm of a glitch-free join whose other arm is volatile, and recovery never stalls the join. The previously-unwritten "journal only context-free roots" restriction is retired.

You get both for free by using journaled cells and `recoverFrom`; there's no new API to call.

**Also:** checkpoint now refuses to truncate a WAL when the snapshot would capture nothing recoverable (previously silent data loss), and a departed member's watermark row is now closed on evict/unpublish (previously it constrained downstream replica-fed frontiers forever).

## Glitch-free settlement across sharded + replicated instance sets

**What's new.** Completeness used to quantify over *all* members, so the moment shards joined the mesh a disjoint-interest instance would stall a WAIT consumer forever. Settlement now quorums over the *covering* subset — the live, open members whose interest admits the key — so a board fed by an instance set that is both sharded and replicated never surfaces an uncovered value and still makes progress.

**How to use it.** Opt in on a glitch-free cell:

```kotlin
glitchFreeCell.useReplicaFrontier(
    replicaFrontier = replication.replicaFrontier(logicalId),                 // covering-subset quorum
    originTags = { inv -> inv.originTimestamps() },
    originKeys = { inv -> inv.keyedOriginTags() },   // omit ⇒ unfiltered ⇒ today's behavior
)
```

Leaving `originKeys` at its default is byte-for-byte the previous behavior. A **DEGRADE** variant (`replicaFrontier(logicalId, degrade = true)`) additionally drops a *parked* covering member from the quorum and restores it on resume, so the board keeps producing across a covering instance's park (see attention, below).

## Stackable port policies

**What's new.** Inlet behaviors used to be single slots that silently stomped each other (installing replication's catch-up hook overwrote the frontier's, etc.). Inlets now have an ordered, fixed-tier policy chain; install order is irrelevant, tier order is authoritative.

**How to use it.** `civictech.cell.port.InletPolicy` with tiers `ADMIT` (may drop, never hold) → `GATE` (hold FIFO) → `ALIGN` (reorder; at most one) → `ACTIVATE` (cold-park). Pull behaviors are now installable policies too: `PullOnOpen` (emit a `StateRequest` on `EdgeOpen`) and `PullServe` (answer pulls). A dropping `ADMIT` policy must declare `mintsProgressAck` or the chain refuses to let it sit above an `ALIGN` (it would stall the downstream). Installing two `ALIGN` policies throws at install time.

## Negotiated attachments (behavior change)

**What's new — and this is the one intentional behavior change in the release.** `tap` and `streamTo` used to bypass the handshake entirely — no policies, no allowlist, no nature reconcile, no `EdgeOpen`. They now negotiate **by default**.

**How to use it / what changed.**

```kotlin
outlet.tap(consumer)                        // now negotiates (was a raw bypass)
outlet.streamTo(target, at = inlet)         // now negotiates
outlet.tap(consumer, negotiated = false)    // opt back into the old raw bypass
```

Negotiated attachments run the full handshake, so they can be refused by an allowlist policy or a nature mismatch, and they announce as links. `Link` now carries a `role` (`LinkRole.Consume` default, `Observe` for taps); the wave frontier counts only `Consume` edges, so an announced tap never gates a join. For non-local/routed targets, `streamTo`/`tap` fall through to the historic path unchanged.

## Composed natures are detectable, and dangerous compositions are refused

**What's new.** A cell's structural properties are now declared and detectable, and two mismatches that used to fail *silently* now fail *loudly* at the boundary:
- an unwaved producer into an `ALIGN` (glitch-free) inlet used to drop silently → now a typed refusal on `WAVE_PARTICIPATION`;
- a non-`Scoped` delta into a partial-interest inlet used to over-deliver → refusal on `INSTANCE_SCOPING`;
- overlapping interest assigned to a non-mergeable structure used to double-count → refusal on `MERGE_IDEMPOTENCE`;
- an exclusive (`Owned`) payload routed to N instances used to be double-consumed → refused unless the assignment is disjoint (`OWNERSHIP`).

Structural natures that are *normal* to cross a link (a volatile consumer of a durable producer) deliberately do **not** refuse.

**How to use it.** Query a cell's composed manifest:

```kotlin
import civictech.cell.manifestOf
import civictech.gen.wire.Manifest

manifestOf(board.javaClass)      // e.g. {GLITCH_FREE}
manifestOf(shard.javaClass)      // e.g. {DURABLE, REPLICATED, PARTITIONED}
```

Manifest values are `GLITCH_FREE, DURABLE, REPLICATED, PARTITIONED, PULL_SERVING, GATED`. They are derived from existing marker interfaces (no new annotations) and are used for spawn-time diagnostics and cross-wire forward-compat — never by link reconciliation. Nature vectors now also ride the link-request frame, so a genuine cross-host nature mismatch is detected at link time (before, both ends independently resolved the same local descriptor and a real mismatch slipped through).

## Declare a whole instance set in one step

**What's new.** Producing a heterogeneous set of instances (N shards × M replicas, each with its own interest/placement/journal) used to mean hand-wiring N mechanisms. There's now one declaration surface that lowers to plain spawns + formation assignments.

**How to use it.** `GraphBuilder.instanceSet(handle, logicalId, factory, instances)`:

```kotlin
builder.instanceSet(
    handle = "board",
    logicalId = boardId,
    factory = { ref, spec -> ShardCell<Trade>(ref, interest = spec.interest) },
    instances = listOf(
        InstanceSpec(interest = Interest.Slots(setOf(0), 2), instanceId = 0, journalId = "j0"),
        InstanceSpec(interest = Interest.Slots(setOf(1), 2), instanceId = 1, journalId = "j1"),
    ),
)
```

A fully-default declaration lowers to a `GraphSpec` equal to the hand-written form, so it replays deterministically onto fresh hosts. Mis-compositions are refused *at declaration* naming the axis — e.g. partitioning a `SINGLETON` cell, or a `DURABLE` cell on a journal-less host.

## Rolling promotion of a replicated node

**What's new.** `promote` was single-instance only. You can now roll a new implementation through a replica set one instance at a time, with no torn reads across the swap.

**How to use it.** `Promotion.promoteReplica(...)` — the candidate **reuses the incumbent's `CellRef`**, which makes the swap indistinguishable from crash-recovery (all mesh identity derives from the ref); surviving replicas play the retained incumbent; `COMMIT` re-points gossip links via `Replication.rebind` (additive — single-instance `promote` is unchanged). Precheck refuses a candidate with a different ref or a fresh-epoch (T2) catch-up for a replicated cell.

## Exactly-once effects on a replicated sink

**What's new.** An `Effectful` cell replicated over Total/overlapping interest used to fire its external effect once per replica. Now a replicated effect fires **exactly once** per logical delta.

**How to use it.** Run the effectful sink under `SingleWriterReplication`: the elected leader fires the effect; followers are suppressed via the shadow no-op-serve path; `LeaderMark` fencing covers handoff so a deposed leader can't double-fire. Disjoint-interest effectful cells are exactly-once *by construction* and need no authority. Forming a replicated effectful cell on the mergeable mesh **without** a declared authority is refused at formation.

## Attention scatter and a typed Stall/Resume family

**What's new.** Attention now scatters by interest overlap (a covering shard is attended, a non-covering one parks), a parked instance is treated as *stalled, not dead* by the router/mesh, and there's a typed `Stall`/`Resume` family: a suspended covering member publishes a stall; a WAIT board holds (unchanged), a DEGRADE board drops it from the covering quorum and restores it on resume (post-resume replay is a catch-up baseline). A terminal stall re-scopes with a `GlitchViolation`; the evict-and-close path is the degenerate terminal case.

**How to use it.** WAIT is the default (unchanged behavior); opt into DEGRADE via the covering-quorum frontier (`replicaFrontier(..., degrade = true)` above). `WatermarkCell.suspend()`/`resume()` drive the recoverable stall on the delivered-watermark lattice.

## Internal cleanups (no behavior change)

- One `ParkQueue<T>` primitive replaces five hand-rolled append-in-order/hold/drain-once buffers (cold-port tail, red-light buffer, location park, suspended-cell park, repartition flip buffer). Byte-identical everywhere.
- The frontier's previously-silent unmatched-edge drop is now a counted diagnostic (a tripwire; the real fix is the replay-stable identity above).

---

## Known limitations / not yet there

Grounded in what's merged — these are honestly deferred, not hidden:

- **Partial-interest pull does not cross the wire yet.** A scatter-gather `pull` fans only to the interest-overlapping shards, but the `scope` on a `StateRequest` is `@Transient` (the `Interest` algebra isn't kotlinx-serializable), so a *sub-slice* scope doesn't ride a real bridge — a cross-host pull returns each shard's full slice (correct, just not narrowed). In-process pulls narrow fully.
- **The instance-set epoch lattice isn't wired into the runtime router.** `InstanceSet`'s per-instance `(interest, epoch)` max-register is implemented and tested, but the live router reads `shard.interest` directly and assumes a single routing authority (in-order WAL replay). Leaderless concurrent reassignment is out of scope; the router keeps a scoped ledger rather than sourcing replay purely from the mesh.
- **Membership convergence for unknown joiners is closed (FU-2).** The creation fence (R13) turns a *known-but-not-yet-gossiped* covering member into a conservative hold, DEGRADE handles a *recoverable* stall, and the **converged-membership barrier** now closes the last half: a covering member the local view hasn't learned about at all. Each replica announces its existence on the delivered-watermark companion — a *transitively*-gossiped, idempotent CRDT that converges membership more completely than the point-to-point topology announcements feeding `instancesOf`. A settling node whose companion lists a member slot its own view has not accounted for (nor `closed`, nor `suspended`) holds every keyed wave (`replicaFrontier(membershipBarrier = true)`, default on), never releases early, and releases the instant its view converges. The residual is now only the Byzantine/permanent-partition case (a member that announces then vanishes without a clean `close`), which the WAIT board's latency-unbounded contract already governs; the unfiltered default path (`key == null`, no covering quorum) is untouched and byte-identical.
- **Partitioned rolling promotion is documented but only replicated is tested.** `promoteReplica`/`rebind` accept a `ShardCell` (it's `Replicable`) and the shard-by-shard form is specified, but the shipped test covers the replicated case only.
- **DURABLE placement is a diagnostic, not a hard refusal.** Spawning a durable-capable cell on a journal-less host is *counted and surfaced*, not refused — because running a durable-capable cell volatile (rebuilt from an upstream WAL) is legitimate. The declaration surface (`instanceSet`) *does* hard-refuse `DURABLE`-on-journal-less at build time.
- **No adapter synthesis.** Nature reconciliation is only ever "compose" or "refuse" — there is deliberately no auto-inserted adapter/planner. A refused link is a loud, typed error you fix by wiring, not something the framework silently bridges.
- **Frontier design decided as-is (no multiplex ports).** A research spike concluded the static-link frontier model (static links + absorb-acks + interest-scoped quorum + per-edge source sets) is sufficient for every structure currently built; the dynamic multiplex-port mechanism (G-13) is explicitly *not* needed and not built.
