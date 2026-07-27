# ComputeNet — Composition Plan: the braided route to the vision

> **Generated**: 2026-07-25 · companion to [COMPOSITION-STATUS.md](COMPOSITION-STATUS.md)
> (problem statement) and [FEATURE-STATUS.md](../../FEATURE-STATUS.md) (feature ledger).
> **Provenance**: three independently-briefed designers attacked the obstacle from
> deliberately different angles — (1) a nature type-system with negotiated adapter
> stacks, (2) substrate dissolution, (3) demand-driven empirical sequencing. Their
> full proposals are Appendices A–C. This document is the synthesis; the executable
> ticket decomposition is [COMPOSITION-TICKETS.md](COMPOSITION-TICKETS.md).

## The vision being served

Arbitrary pairing of natures in one graph — push/pull, lazy/eager, in-memory vs
change-logged, replicated vs local, partitioned vs atomic (many partition
structure types), glitch-free vs not — working out of the box with unsurprising
defaults, reconfigurable per region; same-nature cells link directly, differing
natures bridged by (stackable) adapter ports.

## Convergent findings (all three designers, independently)

1. **The bridged-handshake leak is everyone's first fix.** Remote links must run
   the same negotiation as local links (`WireEdgeLink.bridgeTo/bridgeFrom`
   currently bypass `handshake()` entirely).
2. **Partitioning is the crisis axis** (11/14 pairs untested, in-process only) and
   **glitch-free × wire is the cheapest high-value unlock** (mostly funded by the
   proposed E2/E3 milestones already).
3. **Nobody builds the universal adapter framework now.** All three defer the full
   15-axis mismatch matrix, membrane couplings (G-53), and speculative adapter
   taxonomies as unearned.

## The decisive insight (Appendix B)

**8 of 9 nature axes are one substrate wearing different clothes** — carried by
four load-bearing substrates that already exist:
(a) the wave/tag/frontier algebra, (b) the single host-intake funnel,
(c) state-as-delta-from-empty, (d) park/replay Buffering.

- Color, push/pull, attention, location, cycles: **already dissolved** (shipped).
  The repo's flagship precedent: ADR-2's four color bridges degenerated to zero
  classes because the uniform host queue dissolved the difference.
- Durability: dissolved but **mis-grained** — the journal tee is a `ManagedHost`
  ctor param; moving it to a per-cell predicate delivers "part in-memory, part
  change-logged" at cell granularity.
- Glitch-freedom: genuinely a **per-consumer frontier policy** masquerading as a
  wrapper cell; as an inlet policy it travels with the inlet and composes with
  every other axis for free.
- **Partitioning is replication with a different interest predicate**: total
  interest + idempotent-union = replication; disjoint key-interest +
  disjoint-union = partitioning; overlapping partial interest = sharded
  replication — *free*. "Many partition types" = many `Interest` assignment
  functions over one mesh, not many composite cells.

**The irreducible remainder** — what can never dissolve — is one codec
(`WireCodec`, objects↔bytes) plus four typed refusals: ownership linearity,
non-idempotent merge on a gossip mesh, non-monotone operators inside cycles,
wrong-color co-hosting. Consequence: the type system only ever needs to police
**~4 axes, not 15**.

## The braided plan

### Phase 1 — Location-transparent frontiers (funded by E2/E3, pulled forward)

Two parallel tracks + one independent small track:

- **Track A — glitch-freedom crosses the bridge**: fix the bridged-handshake
  leak; `EdgeOpen`/`EdgeClose`/`Progress` cross the wire; absorb-acks through the
  operator suite; extract `WaveFrontier` into a **per-inlet frontier policy**
  (`GlitchFreeCell` becomes sugar over it). Closes empty pairs A–F, A–O.
- **Track B — cross-replica frontier**: the delivered-watermark lattice
  (`WatermarkCell`, gossiped pointwise-max) feeding a `ReplicaFrontier` read in
  wave settlement. Closes A–B. (= E3.1–E3.4 with a concrete target.)
- **Track C — per-cell journal tee**: `journalFor(cellRef)` at the intake funnel;
  per-cell recovery/checkpoint (frontier records are already per-cell). Closes
  the durable↔ephemeral granularity gap.

No architectural bet is spent in Phase 1; every ticket sharpens machinery the
E-plan already proposed, with 100-seed tests written first.

### Phase 2 — Partition ⊂ replication + the composition probe

The one place the synthesis **overrides** a single designer: the empirical
route's "distributed partitioned cell" wave is implemented via the dissolution
route's substrate — **instance-sets-with-interest** — rather than by extending
the bespoke `PartitionedCell` composite. Partitioning thereby *inherits*
replication's entire existing composition surface (wire, journal, catch-up,
park/replay) instead of re-earning it pair by pair.

Exit criterion: **`:demo:exchange`** — the "Agora for composition" — a two-JVM
regional order board combining glitch-free + replicated + partitioned + durable
+ wire in one graph (7 empty pairwise cells filled), with
`ExchangeCompositionExitTest` (100 seeds, repartition racing a shard migration,
peer kill -9/recover, late joiner; controls that must diverge).

### Phase 3 — The minimal type system, demand-scoped

The negotiation mechanism from Appendix A (`NatureVector` on descriptors via the
G-60 KSP sweep; pure `reconcile()` in `handshake()`; `Rejected(String)` →
`Rejected(mismatch: NatureMismatch)` backward-compatibly) — but scoped by
Appendix B's result to the **~4 irreducible axes** whose failure mode today is
silent drop: ownership class, merge-idempotence, color, monotonicity — plus
whatever mismatches the Phase-2 probe actually hit. Automatic adapter
**insertion** (Appendix A's planner + fixed-rank stack order) is built **only
if** the probe demonstrates repeated hand-insertion of the same stacks.

### Why this order wins

Phase 1 risks nothing and closes the most embarrassing empty cells; Phase 2
makes the single decision that most reduces future work (partition ⊂
replication) at the moment a real demo forces it; Phase 3 spends the type-system
budget only on what dissolution provably cannot absorb — turning silent drops
into loud typed refusals rather than building a speculative nature algebra.

### Explicit deferrals (from all three designers)

- Universal adapter framework / full 15-axis mismatch matrix — Phase 3 evidence
  first.
- Auto-insertion planner + canonical stack-rank table — gated on probe evidence.
- Membrane Symport/Antiport couplings — research-gated (G-53 / 95 §R3).
- Leader election / automatic failover (R1/G-44) — probe ships explicit failover.
- Placement engine (R5/G-61) — shards placed manually; the probe measures what
  auto-placement would be worth.
- Weighted Z-set family (E6), promotion × replication (K–B) — deferred with
  named triggers (`:demo:exchange` iteration 2's effectful/promoted alert sink).
- Partition-structure taxonomy — one structure ships; taxonomy waits for a
  second real demand.

---

# Appendix A — Route 1: Nature-typed negotiation (full proposal)

## Granularity framing

| Scope | Axes | How it reaches the handshake |
|---|---|---|
| per-PORT | consistency, delivery (push/pull), exclusive, cardinality, direction | on the `Port` object |
| per-CELL | durability, replication, partition, color | folded into the port's vector at `registerPort` |
| per-LINK | the reconciled vector + inserted adapter stack | the returned `Link` |
| per-HOST (today) | durability | **key move: pull down to per-CELL** |

The handshake only ever compares **two port vectors** — substrate natures are
pre-projected onto ports, keeping negotiation a local two-object comparison
(which is what makes the wire path tractable).

## Core abstraction

```kotlin
enum class NatureAxis { CONSISTENCY, DELIVERY, DURABILITY, REPLICATION, PARTITION, LOCALITY }
sealed interface NatureLevel { val axis: NatureAxis }
// e.g. Consistency: RAW ⊑ WAVE_COMPLETE; Delivery: PUSH, PULL ⊑ PUSH_PULL; …

@JvmInline value class NatureVector(val levels: Map<NatureAxis, NatureLevel>) {
    fun level(axis: NatureAxis) = levels[axis] ?: defaultOf(axis)   // absent ⇒ today's behavior
    companion object { val DEFAULT = NatureVector(emptyMap()) }      // shared singleton, zero-alloc
}

// PortDescriptor gains `natures: NatureVector = DEFAULT` (KSP-emitted, G-60 slot)
// Port gains       `val natures: NatureVector get() = DEFAULT`

object NatureNegotiation { fun reconcile(offered: NatureVector, required: NatureVector): Reconciliation }
sealed interface Reconciliation {
    object Direct : Reconciliation                          // identical/subsumed → zero cost
    data class Adapt(val stack: List<AdapterSpec>) : Reconciliation
    data class Refuse(val mismatch: NatureMismatch) : Reconciliation
}
data class NatureMismatch(val axis: NatureAxis, val offered: NatureLevel, val required: NatureLevel)

// Backward-compatible typed verdict:
data class Rejected(val mismatch: NatureMismatch?, val reason: String) : LinkResult {
    constructor(reason: String) : this(null, reason)        // existing call sites & test strings intact
}
```

Hook: inside `handshake()` after policies, before install. `Direct` fast-path is
a reference-equality check on `NatureVector.DEFAULT` — same-nature links stay
literally zero-cost.

## Subsumption/adaptation table

| Axis | subsumes (→ Direct) | adapter exists | refuse |
|---|---|---|---|
| CONSISTENCY | producer WAVE_COMPLETE, consumer RAW | RAW→WAVE_COMPLETE: `GlitchFreeCell` | — |
| DELIVERY | producer PUSH_PULL vs either | PUSH→PULL: `PullCacheCell` | PULL required, PUSH-only, no cache |
| DURABILITY | producer DURABLE, consumer EPHEMERAL | placement-first (host journal); `JournalTeeCell` fallback only | placement impossible |
| REPLICATION | LOCAL→LOCAL | LOCAL→REPLICATED: `Replication.replicate` mesh | SINGLE_WRITER fed by `Leased` |
| PARTITION | ATOMIC/ATOMIC | ATOMIC↔SHARDED: `PartitionedCell` scatter/gather | structure↔structure (until a 2nd structure exists) |
| LOCALITY | co-located | cross-host: bridge pair (already automatic) | policy-forbidden placement |
| exclusive/cardinality/cycle/color | — | — (not adaptable) | **refuse, typed** |

## Adapter-stack selection

- `AdapterSpec(axis, from, to, cellFqn, rank)` via `@NatureAdapter` on wrapper
  cells, ServiceLoader-swept into `ContractModule.adapters`.
- **Non-commutativity resolved by one normative rank table**, consumer-inward →
  wire-outward: `CONSISTENCY(10) → DELIVERY(20) → DURABILITY(30) → PARTITION(40)
  → REPLICATION(50) → LOCALITY(60)`. Bridge always outermost (serialization is
  the physical boundary); glitch-free always innermost (per-consumer property).
  Changing the order is a one-line reviewable event, never a per-link decision.
- **Insertion ownership**: planner is pure; GraphSpec lowering owns insertion for
  declarative graphs (it already turns declarations into spawn+connect);
  imperative `connect` auto-inserts same-host only (`LinkOptions(autoAdapt =
  false)` opts out → typed reject); cross-host insertion defers to GraphSpec.
- Inserted adapters are **real, visible cells tagged `synthetic`** — never
  hidden ("links are the topology"); tooling folds them by tag.

## Migration tickets (wave style)

1. **W5.1** vocabulary + default-identity negotiation, zero behavior change
   (`NatureDefaultsPreserveBehaviorTest`; full suite stays green).
2. **W5.2** KSP emits natures via marker interfaces, mirroring the existing
   `CellColor` scan (`NatureDescriptorSweepTest`).
3. **W5.3** adapter registry + planner + local single-axis auto-insertion
   (`AutoGlitchFreeInsertionTest`).
4. **W5.4** delivery axis closes the StateRequest silent-drop → link-time typed
   reject (`PullCapabilityNegotiationTest`).
5. **W5.5** bridged links run the handshake with the peer's vector resolved from
   the shared descriptor registry; assert `localVerdict == remoteVerdict`
   (`BridgedHandshakeTest`).

## YAGNI cuts

No Symport/Antiport; no re-partition adapter zoo; no push→pull poll adapter; no
nature *inference*/global solver (declared natures, pairwise-local negotiation);
no cross-host runtime auto-insertion from imperative connect; no new adapter
stratum (adapters stay ordinary cells).

---

# Appendix B — Route 2: Substrate dissolution (full proposal)

## The four load-bearing substrates

- **(a) wave/tag/frontier algebra** — every edge carries `(sourceId, counter)`;
  frontier fold, watermarks, `Progress`, edge markers, merge tags, attention LWW
  are the same `(UUID, Long)` shape used four ways.
- **(b) the single host-intake funnel** — one non-blocking offer; hop guard;
  journal tee; OPEN/SATURATED/CLOSED word; coalesce; management exemption.
- **(c) state-as-delta-from-empty** — one primitive backing catch-up, pull,
  replication sync, repartition replay, promotion-T2.
- **(d) park/replay Buffering** — one pattern at four triggers (location,
  attention, saturation, supervision).

## Axis-by-axis dissolution verdicts

| Axis | Verdict | Into |
|---|---|---|
| Execution color | **Dissolved (shipped)** — ADR-2's 4 bridges → 0 classes | (b) |
| Push/pull | **Dissolved (shipped)** — `StateRequest` + catch-up baseline; racing both loss-free via tags | (c)+(a) |
| Durability | **Dissolved but mis-grained** — host ctor param → per-cell tee | (b) |
| Replication | **Dissolved (shipped)** for the mergeable class; remainder is a *refusal* | (c)+links+(d) |
| Glitch-free | **Dissolvable** — a per-consumer frontier policy wearing a cell costume | (a) |
| Location | **Dissolved (shipped)**; bridged-handshake skip is a bug to close, not an adapter | (b)+(d) |
| Attention | **Dissolved (shipped)** — suspension and backpressure are one mechanism at two triggers | (b)+(d) |
| Cycles | **Dissolved (shipped)** — fresh-wave mint at the head; wave uniqueness structural | (a)+(b) |
| **Partitioning** | **Dissolvable** — *replication with disjoint interest* | the instance-set substrate |
| Ownership | **Does NOT dissolve** — linear types vs a fan-out plane; freeze + refuse | — |

## The three deepenings

1. **Instance-set-with-interest** (unifies replication + partitioning): per-instance
   `Interest` predicate on `LocationRegistry`/`Replication.maybeLink`; total
   interest = replication, disjoint slots = partitioning, partial overlap =
   sharded replication free. Retires ~6–8 of the 11 empty partition pairs;
   collapses two of the five state-as-delta copies.
2. **Per-cell funnel tee**: `journalFor(cellRef)` at the intake; frontier records
   already per-cell; reveals journal ≡ replica-sink (two `Interest`-selected
   sinks on one emission path).
3. **Frontier fold as per-inlet policy**: `inlet.frontierPolicy = WAIT` consulted
   in host deliver; `GlitchFreeCell` becomes sugar; removes the
   `suspensionRegionOf` special-case; the glitch-free axis stops being a graph
   node to compose and travels with the inlet.

## The irreducible remainder

One codec + four refusals: `WireCodec` (objects↔bytes, shared by wire *and*
journal); ownership freeze/lease-refuse; non-idempotent→non-replicable;
non-monotone-on-cycle stratification; wrong-color co-hosting. **Not a family of
converters** — the 231 tensions need deepened substrates plus a handful of loud
refusals.

## Honest tradeoff vs the type system

Beats it: nothing to build/stack/order for dissolved axes; mismatch made *moot*
rather than detected; new pairs compose for free (partition×X inherits
replication×X); fewer concepts. Loses to it: the irreducible remainder fails
*silently* today and needs typed loud refusals; deepening concentrates blast
radius in the kernel (adapters are additive); per-inlet policies are invisible
in the topology without the descriptor sweep; no static composition check.
**Synthesis: dissolution decides how few axes the type system must police — ~4.**

---

# Appendix C — Route 3: Empirical sequencing (full proposal)

## Ranked pair-closure list

1. **A–F** glitch-free × wire — `GlitchFreeBridgedDiamondTest` (bridge at the
   diamond cut; frame reorder/duplication; control: no `EdgeOpen`/`Progress`
   forwarding → stall or glitch). Forces the bridged-handshake fix **E2 omits**.
   Otherwise funded by E2.3.
2. **A–O** glitch-free × operator suite — `GlitchFreeOperatorSuiteTest` (M11
   pipeline through `observeAligned`; control: absorbing cells without
   `Progress` stall the last wave). = E2.2 + E2.4 verbatim.
3. **A–B** glitch-free × replication — `GlitchFreeReplicaFrontierTest` (two
   peers feed one join from different replicas; partition/heal; control:
   replica-frontier off → mixed-frontier output). = E3.1–E3.4, sequenced early.
4. **C–F/C–B/C–D** partitioned × {wire, replication, journal} —
   `PartitionedShardsAcrossHostsTest` (shards on different hosts; mid-run
   repartition racing a shard migration; control: routing-epoch-blind →
   loss/double-count). **Not funded by E1–E6** — new wave (G-56/M15.4 pulled
   forward).
5. **K–B** promotion × replication — defer (M13.1 epoch work is a heavy
   prerequisite; no demo demands it yet).

## The composition probe — `:demo:exchange`

Two symmetric JVM peers (forking the `shopping` scaffold): per-peer writer
`SetCell`s (orders keyed by region) [F] → union → **partitioned by region,
shards on different hosts** [C, C–F] → per-shard GroupBy(sum), **each shard
aggregate replicated to the peer** [B, C–B, B–F] → `observeAligned` folds
shards + global total into one glitch-free board [A: A–C, A–B, A–F, A–O] → SSE.
Writer intake journaled [D]; kill -9 a peer → recover [D–F, D–B]. Fills 7
empty pairwise cells in one graph. Iteration 2 (deferred): effectful
threshold-alert sink, shadowed and promoted → J–F, J–B, K–B, K–C.

## Incidental vocabulary growth

A1 forces a frontier-participation bit at the bridged link; A2 forces the
delivered-frontier row + replica-fed edge declaration (`WatermarkDelta` *is*
nature metadata on the wire); A3 forces the partitioned-vs-atomic bit **and the
first typed `Rejected(mismatch)`** — §8 of COMPOSITION-STATUS built
demand-driven, three axes' worth, not fifteen.

## What this route cannot reach alone

Auto-insertion (needs Route 1's resolver); lazy/eager as a uniform per-region
knob (needs Route 2's substrate work); the pluggable partition taxonomy; the
complete mismatch matrix. The bet: three axes of demand-driven growth plus one
5-nature probe will show whether the resolver is worth building at all — and
specify it against real mismatches, not a speculative algebra.
