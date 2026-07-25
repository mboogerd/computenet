# Changelog — the composition run

*Covers everything merged to `main` between `b6e59ca` (baseline) and `d40e4ad`
(final gate green). 16 tickets, 7 waves, 3 phases. Written for someone who uses
ComputeNet and did not follow the ticket process.*

The theme of the whole run: **the good properties now compose**. Before it, you
could have a glitch-free view, *or* a replicated cell, *or* a partitioned cell,
*or* durability, *or* a cross-host bridge — but combining them either silently
degraded, stalled, or was simply undefined. Now one graph can be partitioned
**and** replicated **and** durable **and** glitch-free **and** spread over
several hosts, and there is a demo (`:demo:exchange`) plus a 100-seed test that
proves it against batch recompute.

---

## 1. Consistency now survives a host boundary

**Landed:** early in the run (`f94e5ce`), plus the operator follow-up (`cdbe1c2`).

Previously, `WireEdgeLink.bridgeTo`/`bridgeFrom` wired a remote edge by calling
`linking.register` directly — it skipped the handshake entirely. Practical
consequences: link policies didn't run on bridged edges, the peer allowlist
didn't fire (so a trust boundary was only enforced locally), and `EdgeOpen` /
`EdgeClose` / `Progress` never crossed the wire. A glitch-free consumer with one
remote arm therefore either waited forever or emitted a torn value.

Now a bridged link runs the **same** `handshake()` as a local one
(`kernel/.../cell/port/Link.kt`), and edge events plus absorb-acks ride the
existing `PORT_PROTOCOL` frame path.

```
before:  producer@hostA ──bridge──▶ GlitchFreeCell@hostB   // stalls or glitches
after:   producer@hostA ──bridge──▶ GlitchFreeCell@hostB   // settles, no mixed waves
```

You now get, for free, on remote edges: policy evaluation, allowlist rejection
of a denied peer, and correct frontier settlement. Proven by
`GlitchFreeBridgedDiamondTest` (100 seeds of cross-host scheduling + frame
duplication), with two controls that diverge.

**Absorbing operators no longer strand a wave.** `filter`, `mapSet`/`flatMap`,
`union`, `join`, `antijoin`/`semijoin` and `groupBy` now mint a
`Progress(sourceId, thru)` absorb-ack (see `kernel/.../cell/data/AbsorbAck.kt`)
when they swallow a wave without emitting anything. Before, a downstream
glitch-free consumer behind a filter that dropped the last wave would wait
forever for a delta that was never coming. Now the ack rides the outlet's real
links only — a subscriber that isn't topology-aware pays nothing.

## 2. Glitch-freedom is a per-inlet policy, not a wrapper cell

**Landed:** `9e12569`.

The wave-completeness fold moved out of `GlitchFreeCell` into a reusable
`WaveFrontier` (`kernel/.../cell/consistency/WaveFrontier.kt`) implementing the
new `InletFrontier` interface. `FanInlet` gained an opt-in `frontierPolicy` slot.

```kotlin
// before: you had to wrap the cell
val board = GlitchFreeCell(boardApi)

// now: any cell can opt one inlet into wave alignment
inlet.frontierPolicy = WaveFrontier(GlitchFreeCell.WaveMode.WAIT)
```

`GlitchFreeCell` still exists and is unchanged for callers — it is now just sugar
over a pass-through cell whose inlet carries a `WaveFrontier`. `ManagedHost`
keys the suspension region on the *presence of a policy* rather than on the
`GlitchFreeCell` class, so a hand-rolled opt-in cell is treated identically to
the sugar. Modes are `WAIT` (buffer until the wave is complete) and `DEGRADE`.

## 3. A wave can wait for the whole replica set, not just your replica

**Landed:** `f9d563d` → `309dd3d` → `87ec8b9`/`9e26039`.

New machinery:

- `WatermarkDelta` + `WatermarkCell` (`kernel/.../cell/data/Watermark.kt`) — a
  per-source delivered-watermark map merged by pointwise max. Idempotent, so
  gossiping it over the existing mesh is safe by construction; no new protocol.
- `DeliveredFrontier` (`kernel/.../cell/data/DeliveredFrontier.kt`) — the
  per-origin max-contiguous delivered prefix, computed inside
  `SetCell.applyRemote` / `PnCounterCell` where the incoming delta's origin tags
  are still intact.
- `Replication.replicaFrontier(logicalId)` — asks "has the replica *set* reached
  this write's origin wave?"

What this buys you: a glitch-free consumer drawing from two replicas of one
logical cell no longer treats *"my replica delivered it"* as *"the wave is
complete"*. It settles only at the merged watermark across live members. Under
partition/heal, a member that hasn't gossiped holds the frontier; a closed
member stops constraining it.

Important nuance (a rework caught during validation): the replica gate is
declared **per edge**, not per cell — `WaveFrontier.markReplicaFed(outlet, gate)`
for one arm, `installReplicaGate(gate)` for the whole cell. That means a cell can
have one replica-fed arm *and* a local fan-in diamond and stay glitch-free on
both. The first implementation replaced the whole-cell predicate and broke local
arms 50% of the time; `MixedArmGlitchFreeTest` now pins the correct behavior.

## 4. Durability is per cell, not per host

**Landed:** `395c85e`.

`ManagedHost` takes a selector instead of one journal for everything:

```kotlin
// before: whole host journaled, or nothing
ManagedHost(journal = wal)

// now: journal exactly what needs replay
ManagedHost(journalFor = { ref -> if (ref in writerRefs) wal else null })
```

`null` means volatile — never journaled, never replayed. The old `journal =`
constructor still works and maps to the constant selector; the emitted WAL is
byte-identical to before, which a test asserts.

Why you'd want this: journal the intake cells and let derived state
(unions, aggregates, views) be recomputed from the replay. That's exactly what
`:demo:exchange` does — only the writer `SetCell`s are journaled; the union,
GroupBy and board are volatile and rebuilt on restart. Covered by
`MixedDurabilityTest`, with the whole-host selector as the control.

## 5. Replication and partitioning became one mechanism: `Interest`

**Landed:** spec `d81b4c6`, code `7e02d37`.

The new `Interest` predicate (`kernel/.../cell/replication/Interest.kt`) is a
per-instance demand filter over the key/delta space. The gossip linker
(`Replication.maybeLink`) forms a link only where two instances' interests
overlap, and filters every emission down to the *target's* interest before it
rides the link.

One knob, three settings:

| Interest assignment | What you get |
|---|---|
| `Interest.Total` everywhere (the default) | replication — every delta everywhere, dedup by idempotent merge |
| disjoint `Interest.Slots` | partitioning — no cross-shard links at all, union is conflict-free |
| overlapping partial | sharded replication |

Declared via `LocationRegistry.setInterest(ref, interest)`. Unset means `Total`,
so existing replication behavior is byte-identical — nothing you have today
changes.

## 6. Partitioned cells can put shards on different hosts

**Landed:** `c4d6aa9`, hardened by `a5cf896`.

`PartitionedCell` was rebuilt on the instance-set substrate
(`kernel/.../cell/data/PartitionedCell.kt`). Shards are now `ShardCell`
instances of one logical id, each with its own key-`Interest`, each spawnable
onto a real `ManagedHost` and fed by the router over bridges. The router
(`PartitionedShardSet`) *is* the disjoint-interest linker.

- Every routed element carries a `routingEpoch`, which crosses the wire as an
  additive `RoutedCommand` frame field.
- `repartition(newInterests)` is interest reassignment: bump the epoch, each
  shard sheds the range it lost, live state replays into the new owner.
- `beginRepartition` / `endRepartition` open a **buffered flip window**:
  commands touching the moving range park at the router, new owners are seeded
  with the moved-in state, and the buffer drains once on close.
  `LocationRegistry.hold/release` parks a migrating shard's deliveries per-ref,
  so a held range never blocks the other ranges.

Result: a mid-run repartition racing a shard migration loses nothing on 100
seeds (`PartitionedShardsAcrossHostsTest`). The controls fail loudly —
epoch-blind routing forks a moved group, and an unbuffered flip drops or
double-routes. Single-host `PartitionedCell` usage is unchanged (it is now just
the degenerate placement) and `PartitionedCellTest` stays green.

## 7. `:demo:exchange` — a graph that uses all of it at once

**Landed:** scaffold `eade494`, full composition `500c1d7`.

A new module (`demo/exchange/`) with two symmetric JVM peers:

```
region-keyed writer SetCells   (journaled per-cell — §4)
        ↓
   order union, mesh-replicated peer↔peer   (§5)
        ↓
region-PARTITIONED GroupBy(sum) shards, each on its own host   (§6)
        ↓
   disjoint-merge scatter-gather → glitch-free board   (§2, §3)
        ↓
       SSE
```

`ExchangeCompositionExitTest` runs 100 deterministic seeds and asserts the board
equals a batch recompute — through a repartition, a shard migration, a peer
partition/recover, and a late joiner. Two controls diverge: a point-consistent
(frontier-off) board tears, and an epoch-blind repartition forks a region.
Separately, `ExchangeScaffoldTest` runs the real two-JVM path with `kill -9` and
journal replay against the now-partitioned board.

If you want a worked reference for wiring these features together, read
`demo/exchange/src/main/kotlin/civictech/demo/exchange/Main.kt` — it is the
shortest honest example of all five properties in one graph.

## 8. Ports declare their nature, and bad links are refused loudly

**Landed:** `b39e5d0` → `2dd96d4` → `d40e4ad`.

A deliberately tiny type system over ports. Four axes
(`gen/.../wire/ContractDescriptor.kt`):

```kotlin
enum class NatureAxis { OWNERSHIP, MERGE_IDEMPOTENCE, COLOR, MONOTONICITY }
```

carried as a sparse `NatureVector` (empty = `DEFAULT` = today's behavior).

The KSP processor fills these in automatically from markers you already use —
no annotations to add:

- `Replicable` on the cell ⇒ MERGE_IDEMPOTENCE
- `BlockingCell` / `SuspendingCell` ⇒ COLOR
- an `Owned` / `Leased` parameter in the port's contract ⇒ OWNERSHIP
- a `Magnitude` payload ⇒ MONOTONICITY

At link time, `handshake()` calls the pure
`NatureNegotiation.reconcile(offered, required)`
(`kernel/.../cell/port/NatureNegotiation.kt`), which returns either `Direct` or
`Refuse(NatureMismatch)`. An axis refuses only when the *consumer explicitly
declares* a level the producer cannot meet — a stronger producer always
composes, and a default requirement never refuses. So nothing you have today
starts failing.

What changes in practice: mismatches that used to be **silent drops or a crash at
first emission** are now a typed rejection at link time. `LinkResult.Rejected`
gained a nullable `mismatch` field naming the offending axis, offered level and
required level; the old `Rejected(reason)` string constructor still works and
the existing rejection strings are unchanged.

`BridgedHandshakeTest` asserts `localVerdict == remoteVerdict` — negotiation is
location-transparent, riding §1's bridged handshake.

---

## What's still not there

Honest deferrals, all flagged during the run (see `doc/COMPOSITION-ORCH.md`
status log):

- **No partial-sum merge operator.** `GroupByCell` is not `Replicable`, so there
  is no kernel-side `MapDelta` merge for aggregates. `:demo:exchange` designs
  around it by partitioning the **input** and recomputing per shard rather than
  merging aggregates. If you want replicated partial sums that merge, you have
  to build that yourself today.
- **Nature refusal does not cross the wire yet.** `reconcile()` runs on both
  sides of a bridged handshake, but the *nature vector itself* is not shipped in
  the wire descriptor — a remote peer reconciles against what it can resolve
  locally. Cross-wire nature exchange is deferred.
- **COLOR is deliberately excluded from link reconciliation.** Execution color is
  a placement/co-host property validated at spawn, and a link legitimately
  crosses colors (a blocking producer feeding a pure consumer on another host is
  normal). Refusing on it would produce false negatives.
- **No adapter synthesis.** `reconcile()` has no `Adapt` arm, no planner, no
  auto-insertion. That was gated on the probe showing repeated manual adapter
  stacks; it showed none, so it stays unbuilt.
- **Some pairings are proven by unit tests, not by the combined graph.** In
  `ExchangeCompositionExitTest` the replication×partition×durability pairings are
  exercised end-to-end; the glitch-free×wire and glitch-free×operator pairings
  (A–F, A–C, A–O) are proven by their own tests
  (`GlitchFreeBridgedDiamondTest`, `GlitchFreeOperatorSuiteTest`) rather than by
  that one graph.
- **The distributed router still uses a total-interest ledger for replay**, not
  shard-to-shard `StateRequest`. The in-process `routed` ledger stayed in place —
  a partial realization of "retire the bespoke ledger".
- **`WaveFrontier` uses a static link-set frontier.** Real upstream traversal
  ("describe your frontier") needs multiplex ports and is not built; unwaved
  traffic passes straight through.
- **Not ticketed at all** (triggers recorded in `doc/COMPOSITION-PLAN.md`):
  membrane couplings, leader election, a placement engine, a partition-structure
  taxonomy, promotion × replication, and the weighted operator family.
