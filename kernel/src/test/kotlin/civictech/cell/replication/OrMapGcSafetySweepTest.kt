package civictech.cell.replication

import civictech.cell.Timestamp
import civictech.cell.data.OrMapCell
import civictech.cell.data.WatermarkCell
import civictech.cell.data.delta.TaggedMapDelta
import civictech.testkit.dst.CheckRegistry
import civictech.testkit.dst.DepartEvent
import civictech.testkit.dst.DepartureMode
import civictech.testkit.dst.DstCheck
import civictech.testkit.dst.DstWorld
import civictech.testkit.dst.DuplicateFault
import civictech.testkit.dst.FaultPlan
import civictech.testkit.dst.GraphRegistry
import civictech.testkit.dst.GraphSpec
import civictech.testkit.dst.PartitionFault
import civictech.testkit.dst.ReorderFault
import civictech.testkit.dst.churn.ChurnCheckFailure
import civictech.testkit.dst.churn.ChurnMesh
import civictech.testkit.dst.churn.ChurnPlan
import civictech.testkit.dst.churn.ChurnSeeds
import civictech.testkit.dst.churn.ChurnWrite
import civictech.testkit.dst.churn.MeshConvergences
import civictech.testkit.dst.churn.MeshPayload
import civictech.testkit.dst.churn.MeshPeers
import civictech.testkit.dst.churn.ReferenceFold
import civictech.testkit.dst.dstSweep
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.io.File
import kotlin.test.assertTrue

// ================================================================================================
// computenet-9sm.8.7 — the OR-MAP GC safety sweep. See [OrMapGcSafetySweep] for the model.
// ================================================================================================

/**
 * `[KE3-23]` / `[KE3-20]` for the **OR-MAP** reclaimer: the same seeded churn sweep
 * [GcSafetySweep] runs against `SetCell`, on `MeshPayload.OR_MAP` and `OrMapCell`.
 *
 * ## Why this is a COPY and not a generalisation of [GcSafetySweep]
 *
 * 9sm.8-D10, settled. `GcSafetySweep` is `SetCell`-typed throughout — `cell.compactBelow`,
 * `cell.fencesAny`, `cell.fencedAmong`, `tagsIn(cell: SetCell<String>)` — and its own KDoc
 * sanctions copying its file-private rig rather than generalising it. `GcSafetySweepTest.kt` is
 * additionally claimed by `computenet-0ade` and is READ-ONLY to this task. What IS shared is the
 * observation plumbing that carries no payload type: [GcViolation], [GcObservations],
 * [GcObservationRegistry] and [GcTotals] are reused verbatim from that file (same package, same
 * module), so the two sweeps' counters mean the same thing and the copy is the rig, not the
 * accounting.
 *
 * ## The three arms, unchanged in shape
 *
 * - **STABLE** — the PRODUCTION trigger. `OrMapCell.snapshot()` reads the stability hook
 *   `Replication.trackDeliveries` installs (`Replication.kt`'s `cell.onStability {
 *   stableFrontier(logicalId) }`) and runs `compactBelow` at the frontier it answers, under one
 *   hold of the cell's monitor, before serialising. This arm reclaims nothing itself: the
 *   frontier, the discard rule and the `(key, dot)` fence are all the production path's. The
 *   discarded count is DIFFERENCED across the call — `snapshot()` returns state, not a number —
 *   using [dotsIn], which reads `OrMapCell.state()` (a copy-out that reclaims nothing).
 * - **LOCAL** — `compactBelow(localDeliveredFrontier)`, the wrong seam, permanently a harness
 *   seam: no production caller reclaims at the locally-delivered frontier and `[KE3-30]` says
 *   none may.
 * - **NONE** — the no-reclaimer control, which measures the rig's own divergence floor in the
 *   same run.
 *
 * ## The observable the OR-SET sweep cannot have: per-key VALUE agreement
 *
 * `SetCell` membership is the whole of an OR-set replica's exposed value. An OR-map replica has
 * a second one — `value(key)`, the add-wins pick over the key's live dots — and reclamation is
 * only invisible to the value if BOTH agree. So this sweep's cross-replica check compares the
 * pair `(membership(), key -> value(key))` and reports a value-only divergence as its own class
 * ([VALUE_DIVERGENCE_FAILURE]) rather than folding it into the membership one, so the membership
 * counts stay comparable, seed for seed, with the OR-set sweep's. The per-replica resurrection
 * check likewise compares the cell against its own emitted `ReplicaConvergence` fold
 * (`TaggedMapDelta` merge) on membership AND on `value(key)` ([VALUE_FOLD_DRIFT_FAILURE]).
 *
 * **What that observable can and cannot see here, stated honestly.** `MeshPeer.write` puts
 * `key = "$name-$ordinal"` with `value = "$ordinal"`, and a key is written exactly once by
 * exactly one peer, so there are no CONCURRENT puts on a key in this workload and the add-wins
 * pick is never exercised on a contended key. What the value observable therefore catches is a
 * replica whose live dot set for a key is wrong — a discarded put-dot that leaves the key present
 * with a different (or no) value, or a re-admitted one — not a mis-resolved concurrent write. A
 * multi-writer key workload is a different rig and is out of this task's scope.
 *
 * ## Honesty
 *
 * A bounded-schedule check over a finite seed range, not a proof — the same clause
 * [GcSafetySweep]'s KDoc carries, for the same reason, filed under the same DISPUTES entry.
 */
object OrMapGcSafetySweep {

    enum class Trigger(val id: String, val checkId: String) {
        STABLE("ormap-gc-safety-sweep-stable", "ormap-gc-safety-stable"),
        LOCAL("ormap-gc-safety-sweep-local", "ormap-gc-safety-local"),

        /** The no-reclaimer control. See [GcSafetySweep.Trigger.NONE] for why it exists. */
        NONE("ormap-gc-safety-sweep-none", "ormap-gc-safety-none"),
    }

    // Restated from [GcSafetySweep] (they are private to that file). Same values deliberately:
    // the two sweeps are meant to be comparable like for like.
    private const val PEER_COUNT: Int = 3
    private const val OP_SCRIPT_LENGTH: Int = 24
    private const val STEP_BUDGET: Int = 6000
    private const val DRAIN_MARGIN: Int = 1000
    private const val WRITE_START: Int = 300
    private const val WRITE_STRIDE: Int = 200
    private const val REMOVE_LAG: Int = 90

    /** Compaction period. 10 since computenet-qbap — see [GcSafetySweep]'s `K` for the measurement. */
    private const val K: Int = 10

    private const val RECLAIM_UNTIL: Int = STEP_BUDGET + DRAIN_MARGIN

    val faultIds: Set<String> =
        setOf("ormap-gc-park", "ormap-gc-park-b", "ormap-gc-park-c", "ormap-gc-dup", "ormap-gc-reorder")

    private val templatePlan: ChurnPlan =
        ChurnSeeds.plans(0L..0L, StableFrontierChurnSweep.config).single().let { plan ->
            plan.copy(
                writeSchedule = (0 until OP_SCRIPT_LENGTH).map { i ->
                    ChurnWrite(WRITE_START + i * WRITE_STRIDE, plan.peers[i % plan.peers.size], i)
                },
            )
        }

    /**
     * The removes the removes-hook will issue: every odd-ordinal write, [REMOVE_LAG] steps later.
     * The second component is the KEY on this payload (`MeshPeer.remove`'s KDoc), which is the
     * same `"$peer-$ordinal"` string `MeshPeer.write` puts under.
     */
    private val removeSchedule: Map<Int, List<Pair<String, String>>> =
        templatePlan.writeSchedule
            .filter { it.ordinal % 2 == 1 }
            .groupBy({ it.atStep + REMOVE_LAG }, { it.peer to "${it.peer}-${it.ordinal}" })

    /** The keys a fence can ever hold — `compactBelow` records only what it discarded from `dels`. */
    private val fenceableKeys: List<String> =
        removeSchedule.values.flatten().map { it.second }.distinct()

    internal val totals: Map<Trigger, GcTotals> = Trigger.entries.associateWith { GcTotals("ORMAP-${it.name}") }

    fun graphOf(trigger: Trigger): GraphSpec = GraphSpec(trigger.id) { world ->
        ChurnMesh.spec(
            templatePlan,
            payload = MeshPayload.OR_MAP,
            maxPeers = PEER_COUNT,
            aliveUntil = STEP_BUDGET + DRAIN_MARGIN,
        ).builder.build(world)
        world.steps.onStep { w, step -> issueRemoves(w, step) }
        world.steps.onStep { w, step -> if (step <= RECLAIM_UNTIL) compact(w, step, trigger) }
    }

    private val graphs: Map<Trigger, GraphSpec> by lazy { Trigger.entries.associateWith { graphOf(it) } }

    fun graph(trigger: Trigger): GraphSpec = graphs.getValue(trigger)

    // ----------------------------------------------------------------------------- the workload

    private fun issueRemoves(world: DstWorld, step: Int) {
        val due = removeSchedule[step] ?: return
        val observations = GcObservationRegistry.of(world)
        for ((peer, key) in due) {
            if (MeshPeers.find(world, peer)?.remove(key) == true) observations.removesIssued++
        }
    }

    // ---------------------------------------------------------------------------- the reclaimer

    /**
     * The cell's total dot count — every put-dot plus every del-dot it currently retains — read
     * WITHOUT reclaiming, the "before" half of the STABLE arm's `discarded`.
     *
     * `OrMapCell.state()` is the right read here for the reason the OR-set sweep needs
     * `readBounded`: it copies the two dot maps out under the monitor and reclaims nothing, so it
     * is not the thing being measured. Its content is exactly `snapshot()`'s `"puts"`/`"dels"`.
     */
    private fun dotsIn(cell: OrMapCell<String, String>): Int {
        val state = cell.state()
        return state.puts.values.sumOf { it.size } + state.dels.values.sumOf { it.size }
    }

    /** The same count over what [OrMapCell.snapshot] returned — the "after" half. */
    @Suppress("UNCHECKED_CAST")
    private fun dotsIn(serialised: java.io.Serializable): Int {
        val map = serialised as Map<String, Any?>
        val puts = map["puts"] as Map<Any?, Map<Any?, Any?>>
        val dels = map["dels"] as Map<Any?, Set<Any?>>
        return puts.values.sumOf { it.size } + dels.values.sumOf { it.size }
    }

    /** The live dots of [key] at [cell]: every put-dot no del-dot covers. */
    private fun liveDotsOf(cell: OrMapCell<String, String>, key: String): Set<Timestamp> {
        val state = cell.state()
        val puts = state.puts[key]?.keys ?: return emptySet()
        val covered = state.dels[key] ?: emptySet()
        return puts - covered
    }

    @Suppress("UNCHECKED_CAST")
    private fun compact(world: DstWorld, step: Int, trigger: Trigger) {
        if (step <= 0 || step % K != 0) return
        if (trigger == Trigger.NONE) return
        val observations = GcObservationRegistry.of(world)
        for (peer in MeshPeers.all(world)) {
            if (!peer.member) continue
            val cell = (peer.replica ?: continue) as? OrMapCell<String, String> ?: continue
            val frontier = when (trigger) {
                // Reported and used for the [KE3-30] interlock only; on STABLE it is a second,
                // independent read of the same monotone source `snapshot()` reads through the
                // installed hook, with no step between them.
                Trigger.STABLE -> peer.replication.stableFrontier(peer.ref.id)
                Trigger.LOCAL -> peer.replication.localDeliveredFrontier(peer.ref.id)
                Trigger.NONE -> return
            }
            val before = cell.membership()
            val beforeValues = before.associateWith { cell.value(it) }
            val discarded = when (trigger) {
                // THE PRODUCTION TRIGGER. See the object KDoc.
                Trigger.STABLE -> {
                    val dotsBefore = dotsIn(cell)
                    val serialised = cell.snapshot()
                    dotsBefore - dotsIn(serialised)
                }
                else -> cell.compactBelow(frontier)
            }
            val after = cell.membership()
            val afterValues = after.associateWith { cell.value(it) }

            observations.invocations++
            observations.discarded += discarded
            if (discarded > 0) {
                for (key in fenceableKeys) {
                    val instrumentKey = peer.name to key
                    if (instrumentKey !in observations.fencedAtStep && cell.fencesAny(key)) {
                        val openSlots = peer.replication.openSlots(peer.ref.id)
                        val stillHeldBy = MeshPeers.all(world)
                            .filter { it.name != peer.name && it.member }
                            .filter { p ->
                                (p.replica as? OrMapCell<String, String>)?.membership()?.contains(key) == true
                            }
                            .map { p ->
                                val slot = WatermarkCell.slotId(peer.replication.watermarkRef(p.ref))
                                "${p.name}(${openSlots.exclusionOf(slot) ?: "open"})"
                            }
                        observations.fencedAtStep[instrumentKey] =
                            "step=$step stillHeldBy=$stillHeldBy openSet={$openSlots}"
                    }
                }
            }
            // Feature rule 3, on BOTH observables: reclamation is invisible to the value.
            if (after != before) {
                observations.violations += GcViolation(
                    "compaction changed membership", step, peer.name,
                    "added=${after - before} removed=${before - after} discarded=$discarded",
                )
            } else if (afterValues != beforeValues) {
                observations.violations += GcViolation(
                    "compaction changed a key's value", step, peer.name,
                    "changed=" + before.filter { beforeValues[it] != afterValues[it] }
                        .associateWith { "${beforeValues[it]}->${afterValues[it]}" } +
                        " discarded=$discarded",
                )
            }
            // Feature rule 4, the [KE3-30] interlock: an empty frontier certifies nothing.
            if (frontier.perSource.isEmpty() && discarded > 0) {
                observations.violations += GcViolation(
                    "compaction discarded below an empty frontier", step, peer.name,
                    "discarded=$discarded",
                )
            }
        }
    }

    // ---------------------------------------------------------------------------------- the plan

    fun plan(seed: Long): FaultPlan = StableFrontierChurnSweep.churnPlan(seed).withFaults(
        // The adversary is [GcSafetySweep.plan]'s, verbatim except for the fault ids (which must
        // be unique per sweep). Its disjoint-window construction and the four widenings measured
        // and REJECTED are recorded in that function's comments; nothing is re-derived here.
        PartitionFault.park("ormap-gc-park", "peer0<->peer1", from = 1200, until = 1800),
        PartitionFault.park("ormap-gc-park-b", "peer0<->peer2", from = 2400, until = 3000),
        PartitionFault.park("ormap-gc-park-c", "peer1<->peer2", from = 3600, until = 4200),
        DuplicateFault.frames("ormap-gc-dup", "peer1<->peer2", copies = 1, probability = 0.5),
        ReorderFault("ormap-gc-reorder", "peer0<->peer2", window = 3),
    ).toFaultPlan()

    // --------------------------------------------------------------------------------- the check

    /** `membership() − project(emitted-delta fold)`: keys live in the cell its own history removed. */
    @Suppress("UNCHECKED_CAST")
    private fun resurrected(cell: OrMapCell<String, String>, fold: TaggedMapDelta<String, String>): Set<String> =
        cell.membership() - (MeshConvergences.project(fold) as ReferenceFold.Elements).elements

    const val VIOLATION_FAILURE: String = "compaction broke a per-invocation rule"
    const val RESURRECTION_FAILURE: String = "compaction resurrected a removed key"
    const val DISAGREEMENT_FAILURE: String = "live folds disagree after compaction"
    const val MEMBERSHIP_DIVERGENCE_FAILURE: String = "live replicas' memberships diverge after compaction"

    /**
     * The OR-map's own class (9sm.8-D10 clause 2): live replicas AGREE on membership and disagree
     * on some key's `value(key)`. Separate from [MEMBERSHIP_DIVERGENCE_FAILURE] so the membership
     * counts stay seed-for-seed comparable with the OR-set sweep's.
     */
    const val VALUE_DIVERGENCE_FAILURE: String = "live replicas' per-key values diverge after compaction"

    /** A cell's `value(key)` disagrees with its OWN emitted fold's — the resurrection class's twin. */
    const val VALUE_FOLD_DRIFT_FAILURE: String = "a replica's value drifted from its own emitted fold"

    const val FENCE_ATTRIBUTED_DIVERGENCE_FAILURE: String =
        "a diverging key's live dot is in the lacking replica's ReclaimedDots"

    @Suppress("UNCHECKED_CAST")
    fun check(trigger: Trigger): DstCheck = CheckRegistry.register(trigger.checkId) { world ->
        val observations = GcObservationRegistry.of(world)
        val live = MeshPeers.all(world).filter { it.member && it.replica != null }

        observations.violations.firstOrNull()?.let { first ->
            totals.getValue(trigger).absorb(observations)
            throw ChurnCheckFailure(
                VIOLATION_FAILURE,
                detail = "${observations.violations.size} violation(s); first: $first; " +
                    "invocations=${observations.invocations} discarded=${observations.discarded}",
            )
        }

        val resurrections = mutableListOf<String>()
        val valueDrift = mutableListOf<String>()
        for (peer in live) {
            val cell = peer.replica as? OrMapCell<String, String> ?: continue
            val fold = MeshConvergences.of(world, peer.name)?.state(peer.ref) as? TaggedMapDelta<String, String>
                ?: continue
            observations.resurrectionChecks++
            val re = resurrected(cell, fold)
            if (re.isNotEmpty()) {
                val tagView = re.associateWith { k ->
                    "puts=${fold.puts[k]?.keys?.map { it.counter }?.sorted()} " +
                        "dels=${fold.dels[k]?.map { it.counter }?.sorted()} value=${cell.value(k)}"
                }
                resurrections += "peer=${peer.name} keys=$re $tagView"
            }
            // The OR-map's extra observable against the same fold: a key both agree is present
            // must expose the same value at the cell and at the cell's own emitted history.
            val drifted = (cell.membership() intersect fold.membership())
                .filter { cell.value(it) != fold.value(it) }
            if (drifted.isNotEmpty()) {
                valueDrift += "peer=${peer.name} " + drifted.associateWith {
                    "cell=${cell.value(it)} fold=${fold.value(it)}"
                }
            }
        }
        if (resurrections.isNotEmpty()) {
            totals.getValue(trigger).absorb(observations)
            throw ChurnCheckFailure(
                RESURRECTION_FAILURE,
                detail = "${resurrections.size} live replica(s) re-admitted: ${resurrections.joinToString("; ")}; " +
                    "discarded=${observations.discarded}",
            )
        }
        if (valueDrift.isNotEmpty()) {
            totals.getValue(trigger).absorb(observations)
            throw ChurnCheckFailure(
                VALUE_FOLD_DRIFT_FAILURE,
                detail = "${valueDrift.joinToString("; ")}; discarded=${observations.discarded}",
            )
        }

        val cellsByPeer = live.mapNotNull { peer ->
            (peer.replica as? OrMapCell<String, String>)?.let { peer.name to it }
        }
        val membershipsByPeer = cellsByPeer.map { (name, cell) -> name to cell.membership() }
        if (membershipsByPeer.map { it.second }.distinct().size > 1) {
            totals.getValue(trigger).absorb(observations)
            // THE ATTRIBUTION READ, on `(key, dot)`: for each key the live replicas disagree on,
            // the dots that make it live where it IS live, checked against `ReclaimedDots` at
            // every replica where it is NOT. A direct read, not an ordinal-parity inference.
            val union = membershipsByPeer.flatMap { it.second }.toSet()
            val agreed = membershipsByPeer.map { it.second }.reduce { a, b -> a intersect b }
            val differing = union - agreed
            val attributions = differing.map { key ->
                val holders = membershipsByPeer.filter { key in it.second }.map { it.first }
                val liveDots = cellsByPeer
                    .filter { it.first in holders }
                    .flatMap { liveDotsOf(it.second, key) }
                    .toSet()
                val fencedAt = cellsByPeer
                    .filterNot { it.first in holders }
                    .mapNotNull { (name, cell) ->
                        val fenced = cell.fencedAmong(key, liveDots)
                        if (fenced.isEmpty()) null else {
                            name to (fenced.map { it.counter }.sorted() to (fenced.size == liveDots.size))
                        }
                    }
                val holderState = holders.map { name ->
                    val p = live.firstOrNull { it.name == name }
                    "$name{lastDeparture=${p?.lastDeparture} suspended=${p?.suspended} " +
                        "evictDespawned=${p?.lastEvictDespawned} member=${p?.member} " +
                        "value=${cellsByPeer.first { it.first == name }.second.value(key)} " +
                        "fencedAt={${observations.fencedAtStep[name to key]}} " +
                        "membership=${p?.membershipLog}}"
                }
                Triple(
                    key,
                    "$key held=$holders holderState=$holderState " +
                        "liveDots=${liveDots.map { it.counter }.sorted()} fencedAtLacking=" + (
                        if (fencedAt.isEmpty()) "NONE"
                        else fencedAt.joinToString {
                            "${it.first}:${it.second.first}${if (it.second.second) "(all)" else "(partial)"}"
                        }
                        ),
                    fencedAt.isNotEmpty(),
                )
            }
            val detail = "live replicas disagree on membership at quiescence: " +
                membershipsByPeer.joinToString { "${it.first}=${it.second}" } +
                "; differing=$differing" +
                "; attribution=[" + attributions.joinToString("; ") { it.second } + "]" +
                "; discarded=${observations.discarded}"
            if (attributions.any { it.third }) {
                throw ChurnCheckFailure(FENCE_ATTRIBUTED_DIVERGENCE_FAILURE, detail = detail)
            }
            throw ChurnCheckFailure(MEMBERSHIP_DIVERGENCE_FAILURE, detail = detail)
        }

        // THE OR-MAP'S EXTRA CROSS-REPLICA OBSERVABLE (9sm.8-D10 clause 2). Memberships agree at
        // this point, so the keys are common and the comparison is well defined.
        val commonKeys = membershipsByPeer.firstOrNull()?.second.orEmpty()
        val valueDisagreements = commonKeys.mapNotNull { key ->
            val byPeer = cellsByPeer.associate { (name, cell) -> name to cell.value(key) }
            if (byPeer.values.distinct().size > 1) "$key=$byPeer" else null
        }
        if (valueDisagreements.isNotEmpty()) {
            totals.getValue(trigger).absorb(observations)
            throw ChurnCheckFailure(
                VALUE_DIVERGENCE_FAILURE,
                detail = "live replicas agree on membership but not on value: " +
                    valueDisagreements.joinToString("; ") + "; discarded=${observations.discarded}",
            )
        }

        val disagreeing = MeshPeers.all(world).mapNotNull { peer ->
            val convergence = MeshConvergences.of(world, peer.name) ?: return@mapNotNull null
            if (convergence.converged()) null else peer.name to convergence.states().keys
        }
        totals.getValue(trigger).absorb(observations)
        if (disagreeing.isNotEmpty()) {
            // Branch F-A, exactly as on the OR-set: `ReplicaConvergence` folds EMITTED deltas and
            // cannot express compaction, so its disagreement is a limit of the reference fold.
            val memberships = live.mapNotNull { (it.replica as? OrMapCell<String, String>)?.membership() }.toSet()
            throw ChurnCheckFailure(
                DISAGREEMENT_FAILURE,
                detail = "peers with unconverged folds: ${disagreeing.joinToString { "${it.first}${it.second}" }}; " +
                    "membershipsAgree=${memberships.size <= 1} memberships=$memberships",
            )
        }
    }
}

/**
 * The OR-map GC safety sweep — three arms over the same seed range. See [OrMapGcSafetySweep] for
 * the model and the observables.
 *
 * ## What was MEASURED (host darwin/arm64 16-core `MacBoo`, load1 7-9, seeds 1..200, budget
 * 40_000, `K` = 10, base `3bdacc7e7`, 2026-09-08, three consecutive whole-class runs)
 *
 * ```
 *                               run 1        run 2               run 3
 *   STABLE resurrecting         []           []                  []
 *   STABLE fence-attributed     []           []                  []
 *   STABLE membership-diverging [43,89,      [76,148,151,154]    [4,76,154,165]
 *                                145,146]
 *   STABLE value-diverging      []           []                  []
 *   STABLE value-fold-drift     []           []                  []
 *   STABLE discarded            5571         5557                5521
 *   CONTROL discarded           0            0                   0
 *   CONTROL membership-div.     []           [151,173]           [151,173]
 *   LOCAL resurrecting          []           []                  []
 *   LOCAL membership-diverging  []           [4,12,32,89,181]    []
 *   LOCAL discarded             7395         7383                7408
 *   wall time  STABLE / LOCAL   4.8/2.6 s    5.7/2.6 s           4.7/2.6 s
 * ```
 *
 * The arms cost ~2.6-5.7 s each, matching [GcSafetySweep]'s measured 2.3-5.2 s per 200-seed arm,
 * so the three-arm class is ~10 s.
 *
 * **The BS-13 control does NOT reproduce on this payload, and that is a recorded result rather
 * than a gap.** LOCAL resurrected on 0 of 200 in all three runs and diverged on 1 of 3 runs, so
 * no seed can meet the `PIN_RUNS`-of-`PIN_RUNS` bar the bead sets and none is recorded. The
 * negative result, the counting argument behind it, and the mutation evidence below are filed in
 * `doc/kernel-lane-findings.md` `## KE3-42-ORMAP-BS13`, in the shape of that file's `## KE3-20`.
 * What the LOCAL arm asserts instead is the discriminator that IS observable either way — see
 * that arm's KDoc.
 *
 * **The STABLE arm's non-vacuity was MUTATION-CHECKED**, not merely asserted: with
 * `OrMapCell.compactBelow`'s every-dot rule mutated to a per-dot one (a local, reverted edit),
 * the STABLE arm reddened on `FENCE-ATTRIBUTED diverging seeds=[4]`, `resurrecting` still empty.
 * So this sweep sees the discard rule the feature turns on, through the attribution read rather
 * than through resurrection; `OrMapCellCompactBelowTest`'s LOST-del pin is the deterministic
 * backstop for the same rule.
 *
 * ## Method ORDER is load-bearing here, unlike on the OR-set sweep
 *
 * `[ORMAP-BS-13]`'s discriminator compares LOCAL's summed `discarded` against STABLE's **on the
 * same seeds** (9sm.8-D10 clause 3), so the STABLE arm must have run first. JUnit does not order
 * `@Test` methods by default, so this class declares `@TestMethodOrder` explicitly rather than
 * depending on discovery order. The OR-set sweep needs no such thing because its cross-arm reads
 * are printed, never asserted.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class OrMapGcSafetySweepTest {

    @Test
    @Order(1)
    fun `OR-map compaction at the stable frontier is GC-safe across a churn sweep`() {
        OrMapGcSafetySweep.totals.getValue(OrMapGcSafetySweep.Trigger.STABLE).reset()
        val startedAt = System.nanoTime()
        val sweep = MeshConvergences.observing {
            dstSweep(
                suite = "ormap-gc-safety-stable",
                seeds = SEEDS,
                graph = OrMapGcSafetySweep.graph(OrMapGcSafetySweep.Trigger.STABLE),
                checkId = OrMapGcSafetySweep.Trigger.STABLE.checkId,
                budget = BUDGET,
                artifactRoot = stableRoot,
                planFor = OrMapGcSafetySweep::plan,
            )
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        val totals = OrMapGcSafetySweep.totals.getValue(OrMapGcSafetySweep.Trigger.STABLE)
        stableDiscarded = totals.discarded

        stableResurrecting = seedsOf(sweep, OrMapGcSafetySweep.RESURRECTION_FAILURE)
        stableDisagreeing = seedsOf(sweep, OrMapGcSafetySweep.DISAGREEMENT_FAILURE)
        stableDiverging = seedsOf(sweep, OrMapGcSafetySweep.MEMBERSHIP_DIVERGENCE_FAILURE)
        stableFenceAttributed = seedsOf(sweep, OrMapGcSafetySweep.FENCE_ATTRIBUTED_DIVERGENCE_FAILURE)
        val stableValueDiverging = seedsOf(sweep, OrMapGcSafetySweep.VALUE_DIVERGENCE_FAILURE)
        val stableValueDrift = seedsOf(sweep, OrMapGcSafetySweep.VALUE_FOLD_DRIFT_FAILURE)
        val other = sweep.failures.filterNot { it.message in CLASSIFIED }

        println(
            "[ORMAP-BS-12] seeds=$SEEDS elapsedMs=$elapsedMs artifacts=$stableRoot totals=$totals " +
                "${sweep.summary()}\n" +
                "[ORMAP-BS-12] resurrecting seeds=$stableResurrecting\n" +
                detailsOf(sweep, OrMapGcSafetySweep.RESURRECTION_FAILURE, "ORMAP-BS-12 F-B") +
                "[ORMAP-BS-12] F-A fold-disagreeing seeds=$stableDisagreeing\n" +
                "[ORMAP-BS-12] membership-diverging seeds=$stableDiverging\n" +
                detailsOf(sweep, OrMapGcSafetySweep.MEMBERSHIP_DIVERGENCE_FAILURE, "ORMAP-BS-12 DIVERGE") +
                "[ORMAP-BS-12] FENCE-ATTRIBUTED diverging seeds=$stableFenceAttributed\n" +
                detailsOf(sweep, OrMapGcSafetySweep.FENCE_ATTRIBUTED_DIVERGENCE_FAILURE, "ORMAP-BS-12 FENCED") +
                "[ORMAP-BS-12] value-diverging seeds=$stableValueDiverging\n" +
                detailsOf(sweep, OrMapGcSafetySweep.VALUE_DIVERGENCE_FAILURE, "ORMAP-BS-12 VALUE") +
                "[ORMAP-BS-12] value-fold-drift seeds=$stableValueDrift\n" +
                detailsOf(sweep, OrMapGcSafetySweep.VALUE_FOLD_DRIFT_FAILURE, "ORMAP-BS-12 DRIFT") +
                "[ORMAP-BS-12] artifacts=${sweep.artifactPaths}",
        )
        assertTrue(
            other.isEmpty(),
            "unclassified STABLE failures — every failure must be one of the classified classes, " +
                "or the sweep is reporting something this task has not accounted for: " +
                other.joinToString { "${it.seed}:${it.message}" },
        )
        // THE NON-VACUITY DATUM the bead names: `discarded > 0` summed over the run. A STABLE arm
        // whose `snapshot()` finds no installed stability read reclaims nothing and reddens here
        // rather than reading green on a sweep that never reclaimed.
        assertNonVacuous("ORMAP-BS-12", sweep.total, totals)
        assertAdversaryFired("ORMAP-BS-12", sweep)

        assertTrue(
            stableResurrecting.isEmpty(),
            "[KE3-23] the OR-map re-admission fence must resurrect NOTHING at the STABLE " +
                "frontier. A hit means a duplicated or reordered frame re-delivered a dot " +
                "`compactBelow` had discarded and `applyRemote` re-admitted it. Do not narrow " +
                "SEEDS to reach it. resurrecting=$stableResurrecting",
        )
        assertTrue(
            stableFenceAttributed.isEmpty(),
            "[KE3-23] the OR-map re-admission fence CAUSED a membership divergence: a live " +
                "replica lacks a key whose live dot is in that replica's own `ReclaimedDots`, so " +
                "no re-delivery can admit it and the repair emission did not reach the straggler. " +
                "seeds=$stableFenceAttributed",
        )
        // The OR-map's extra observable, asserted on the RIGHT seam: reclamation is invisible to
        // the value, both across replicas and against each replica's own emitted history.
        assertTrue(
            stableValueDiverging.isEmpty() && stableValueDrift.isEmpty(),
            "[KE3-23] OR-map reclamation must be invisible to `value(key)`: " +
                "crossReplica=$stableValueDiverging vsOwnFold=$stableValueDrift",
        )
        assertTrue(
            (stableDiverging + stableFenceAttributed).size <= MAX_STABLE_DIVERGING,
            "[KE3-23]: compacting at the STABLE frontier left " +
                "${(stableDiverging + stableFenceAttributed).size} seeds with permanently " +
                "diverged memberships, above the recorded bound of $MAX_STABLE_DIVERGING. " +
                "diverging=$stableDiverging fenceAttributed=$stableFenceAttributed " +
                "(compare the CONTROL arm, which measures the rig's own floor)",
        )
    }

    /**
     * The no-reclaimer baseline. Asserts only what it can honestly promise: the sweep ran, the
     * adversary fired, NOTHING was reclaimed (`discarded == 0` on every seed — the bead's own
     * witness for this arm), nothing resurrected and no attribution fired. Its divergence count
     * is RECORDED, never pinned: it is a property of the churn rig.
     */
    @Test
    @Order(2)
    fun `the OR-map no-reclaimer control measures the rig's own divergence floor`() {
        OrMapGcSafetySweep.totals.getValue(OrMapGcSafetySweep.Trigger.NONE).reset()
        val sweep = MeshConvergences.observing {
            dstSweep(
                suite = "ormap-gc-safety-none",
                seeds = SEEDS,
                graph = OrMapGcSafetySweep.graph(OrMapGcSafetySweep.Trigger.NONE),
                checkId = OrMapGcSafetySweep.Trigger.NONE.checkId,
                budget = BUDGET,
                artifactRoot = noneRoot,
                planFor = OrMapGcSafetySweep::plan,
            )
        }
        val totals = OrMapGcSafetySweep.totals.getValue(OrMapGcSafetySweep.Trigger.NONE)
        val controlResurrecting = seedsOf(sweep, OrMapGcSafetySweep.RESURRECTION_FAILURE)
        controlDiverging = seedsOf(sweep, OrMapGcSafetySweep.MEMBERSHIP_DIVERGENCE_FAILURE)
        val other = sweep.failures.filterNot { it.message in CLASSIFIED }
        println(
            "[ORMAP-CONTROL] seeds=$SEEDS ${sweep.summary()} totals=$totals\n" +
                "[ORMAP-CONTROL] resurrecting seeds=$controlResurrecting\n" +
                "[ORMAP-CONTROL] membership-diverging seeds=$controlDiverging " +
                "(${controlDiverging.size} of ${sweep.total}) — the RIG's own floor, recorded not pinned\n" +
                detailsOf(sweep, OrMapGcSafetySweep.MEMBERSHIP_DIVERGENCE_FAILURE, "ORMAP-CONTROL DIVERGE") +
                "[ORMAP-CONTROL] artifacts=${sweep.artifactPaths}",
        )
        assertTrue(other.isEmpty(), "unclassified control failures: ${other.joinToString { "${it.seed}:${it.message}" }}")
        // The bead's NONE witness: `discarded == 0` for every seed. `absorb` sums per run, so a
        // zero total over `runs == total` runs is exactly "no seed reclaimed anything".
        assertTrue(totals.runs == sweep.total, "ORMAP-CONTROL: every seed must have absorbed its counters: $totals")
        assertTrue(
            totals.discarded == 0L,
            "the no-reclaimer control reclaimed something, so it is not a control: $totals",
        )
        assertTrue(
            controlResurrecting.isEmpty(),
            "[KE3-23] control: a run that reclaims NOTHING cannot resurrect anything — a hit here " +
                "means the observable is broken, not the system: $controlResurrecting",
        )
        assertTrue(
            sweep.failures.none { it.message == OrMapGcSafetySweep.FENCE_ATTRIBUTED_DIVERGENCE_FAILURE },
            "[KE3-23] control: nothing is reclaimed here, so no diverging key's live dot can be in " +
                "any replica's `ReclaimedDots`. A hit means the attribution read is wrong: " +
                seedsOf(sweep, OrMapGcSafetySweep.FENCE_ATTRIBUTED_DIVERGENCE_FAILURE),
        )
        assertAdversaryFired("ORMAP-CONTROL", sweep)
    }

    /**
     * **The BS-13 control on the OR-map: the wrong seam.**
     *
     * `computenet-9sm.8.7`'s clause 3 anticipated both outcomes. If at least one seed is harmed —
     * a resurrection or a divergence — that is `[KE3-20]` reproduced on this payload. If none is,
     * the negative result is RECORDED (`doc/kernel-lane-findings.md` `## KE3-42-ORMAP-BS13`, in
     * the shape of `## KE3-20`) and is NOT worked around by searching for a friendlier workload,
     * and the arm falls back on the discriminator that IS observable either way: **LOCAL's summed
     * `discarded` strictly exceeds STABLE's on the same seeds.** That inequality is the
     * mechanical statement that LOCAL is the wrong seam — `localDeliveredFrontier` is
     * per-definition at or ahead of `stableFrontier` (it drops the MIN over other open members),
     * so a reclaimer driven from it discards strictly more, whether or not the extra discards
     * happen to break anything on this seed range.
     *
     * Which of the two branches this tree is on is recorded in [ORMAP_BS13_WITNESS] and in the
     * findings entry.
     */
    @Test
    @Order(3)
    fun `OR-map compaction at the local delivered frontier is the wrong seam`() {
        OrMapGcSafetySweep.totals.getValue(OrMapGcSafetySweep.Trigger.LOCAL).reset()
        val startedAt = System.nanoTime()
        val sweep = MeshConvergences.observing {
            dstSweep(
                suite = "ormap-gc-safety-local",
                seeds = SEEDS,
                graph = OrMapGcSafetySweep.graph(OrMapGcSafetySweep.Trigger.LOCAL),
                checkId = OrMapGcSafetySweep.Trigger.LOCAL.checkId,
                budget = BUDGET,
                artifactRoot = localRoot,
                planFor = OrMapGcSafetySweep::plan,
            )
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        val totals = OrMapGcSafetySweep.totals.getValue(OrMapGcSafetySweep.Trigger.LOCAL)

        val resurrecting = seedsOf(sweep, OrMapGcSafetySweep.RESURRECTION_FAILURE)
        val disagreeing = seedsOf(sweep, OrMapGcSafetySweep.DISAGREEMENT_FAILURE)
        val fenceAttributed = seedsOf(sweep, OrMapGcSafetySweep.FENCE_ATTRIBUTED_DIVERGENCE_FAILURE)
        val diverging = seedsOf(sweep, OrMapGcSafetySweep.MEMBERSHIP_DIVERGENCE_FAILURE) + fenceAttributed
        val valueHarm = seedsOf(sweep, OrMapGcSafetySweep.VALUE_DIVERGENCE_FAILURE) +
            seedsOf(sweep, OrMapGcSafetySweep.VALUE_FOLD_DRIFT_FAILURE)
        val other = sweep.failures.filterNot { it.message in CLASSIFIED }
        println(
            "[ORMAP-BS-13] seeds=$SEEDS elapsedMs=$elapsedMs artifacts=$localRoot totals=$totals " +
                "${sweep.summary()}\n" +
                "[ORMAP-BS-13] resurrecting seeds=$resurrecting (${resurrecting.size} of ${sweep.total})\n" +
                "[ORMAP-BS-13] membership-diverging seeds=$diverging (${diverging.size} of ${sweep.total}); " +
                "of which fence-attributed=$fenceAttributed\n" +
                "[ORMAP-BS-13] value-harmed seeds=$valueHarm\n" +
                "[ORMAP-BS-13] fold-disagreeing seeds=$disagreeing (${disagreeing.size} of ${sweep.total})\n" +
                detailsOf(sweep, OrMapGcSafetySweep.FENCE_ATTRIBUTED_DIVERGENCE_FAILURE, "ORMAP-BS-13 FENCED") +
                "[ORMAP-COMPARISON] discarded: STABLE=$stableDiscarded LOCAL=${totals.discarded}; " +
                "witness=$ORMAP_BS13_WITNESS",
        )
        assertTrue(
            other.isEmpty(),
            "unclassified LOCAL failures: ${other.joinToString { "${it.seed}:${it.message}" }}",
        )
        assertNonVacuous("ORMAP-BS-13", sweep.total, totals)
        assertAdversaryFired("ORMAP-BS-13", sweep)

        // THE DISCRIMINATOR THAT IS OBSERVABLE EITHER WAY (clause 3). It needs the STABLE arm's
        // total, which `@Order` guarantees has been taken; the guard below turns a discovery-order
        // regression into a loud failure rather than a silently vacuous comparison.
        assertTrue(
            stableDiscarded > 0L,
            "the STABLE arm's `discarded` total is missing, so the cross-arm discriminator below " +
                "would compare against 0 and pass vacuously. @Order(1) must have run first.",
        )
        assertTrue(
            totals.discarded > stableDiscarded,
            "[KE3-20] the OR-map wrong seam must reclaim strictly MORE than the right one on the " +
                "same seeds: `localDeliveredFrontier` drops the MIN over the other open members " +
                "that `stableFrontier` takes, so a reclaimer driven from it can only discard at or " +
                "ahead of the stable one. LOCAL=${totals.discarded} STABLE=$stableDiscarded",
        )
    }

    // ------------------------------------------------------------------------------- shared bits

    private fun seedsOf(sweep: civictech.testkit.dst.DstSweepReport, message: String): Set<Long> =
        sweep.failures.filter { it.message == message }.map { it.seed }.toSet()

    private fun detailsOf(
        sweep: civictech.testkit.dst.DstSweepReport,
        message: String,
        tag: String,
    ): String = sweep.failures.filter { it.message == message }.joinToString("") { e ->
        "[$tag] seed=${e.seed} ${(e.cause as? ChurnCheckFailure)?.detail}\n"
    }

    private fun assertNonVacuous(tag: String, total: Int, totals: GcTotals) {
        assertTrue(totals.runs == total, "$tag: every seed must have absorbed its counters: $totals of $total")
        assertTrue(totals.invocations > 0, "$tag: the reclaimer never ran: $totals")
        assertTrue(
            totals.discarded > 0,
            "$tag: a sweep whose reclaimer never discarded a dot proves nothing about reclamation: $totals",
        )
        assertTrue(
            totals.resurrectionChecks > 0,
            "$tag: the resurrection observable was never evaluated on a live replica: $totals",
        )
        assertTrue(
            (totals.removesPerRun.minOrNull() ?: 0L) > 0,
            "$tag: some seed issued no removal at all, so it could not have re-admitted anything: $totals",
        )
    }

    /** [GcSafetySweepTest]'s adversary assertion, on this sweep's own fault ids. */
    private fun assertAdversaryFired(tag: String, sweep: civictech.testkit.dst.DstSweepReport) {
        val drawnModes = mutableSetOf<DepartureMode>()
        val inert = OrMapGcSafetySweep.faultIds.associateWith { mutableListOf<Long>() }
        sweep.entries.forEach { entry ->
            val plan = StableFrontierChurnSweep.churnPlan(entry.seed)
            val fired = entry.report?.appliedFaults.orEmpty().filter { it.fired > 0 }.map { it.id }.toSet()
            val plannedEvents = plan.events.map { it.id }.toSet()
            assertTrue(
                plannedEvents.all { it in fired },
                "$tag seed ${entry.seed}: every planned churn event must fire, or the adversary " +
                    "proves nothing; missing=${plannedEvents - fired} fired=$fired",
            )
            OrMapGcSafetySweep.faultIds.forEach { id -> if (id !in fired) inert.getValue(id) += entry.seed }
            assertTrue(
                PER_SEED_FAULT_IDS.all { it in fired },
                "$tag seed ${entry.seed}: ${PER_SEED_FAULT_IDS - fired} must fire on every seed; fired=$fired",
            )
            plan.events.filterIsInstance<DepartEvent>().forEach { drawnModes += it.mode }
        }
        println("[$tag] folded faults inert on: ${inert.filterValues { it.isNotEmpty() }}")
        assertTrue(
            inert.getValue(SWEEP_WIDE_FAULT_ID).size < sweep.total,
            "$tag: folded fault $SWEEP_WIDE_FAULT_ID never fired on ANY seed, so the adversary it claims is absent",
        )
        assertTrue(
            drawnModes.containsAll(DepartureMode.entries),
            "$tag: the sweep must draw every departure mode across its range: drawn=$drawnModes",
        )
    }

    companion object {
        /** Recorded, and never narrowed after a failure. Same range as [GcSafetySweepTest]'s. */
        private val SEEDS = 1L..200L

        private const val BUDGET: Int = 40_000

        /**
         * **Which branch of clause 3 this tree is on.** Set by MEASUREMENT, not by preference; see
         * `doc/kernel-lane-findings.md` `## KE3-42-ORMAP-BS13` for the run that decided it.
         */
        private const val ORMAP_BS13_WITNESS: String =
            "no per-seed BS-13 witness on the OR-map at K=10, seeds 1..200 — see " +
                "doc/kernel-lane-findings.md ## KE3-42-ORMAP-BS13; the arm carries the " +
                "discarded-inequality discriminator instead"

        private val PER_SEED_FAULT_IDS: Set<String> =
            setOf("ormap-gc-park", "ormap-gc-park-b", "ormap-gc-park-c", "ormap-gc-reorder")

        /** A probability-0.5 duplicator legitimately draws nothing on an idle seed. */
        private const val SWEEP_WIDE_FAULT_ID: String = "ormap-gc-dup"

        private val stableRoot = File("build/dst-stability/ormap-gc-sweep-stable")
        private val noneRoot = File("build/dst-stability/ormap-gc-sweep-none")
        private val localRoot = File("build/dst-stability/ormap-gc-sweep-local")

        private var stableResurrecting: Set<Long> = emptySet()
        private var stableDisagreeing: Set<Long> = emptySet()
        private var stableDiverging: Set<Long> = emptySet()
        private var stableFenceAttributed: Set<Long> = emptySet()
        private var controlDiverging: Set<Long> = emptySet()

        /** Summed `discarded` from the STABLE arm — the LOCAL arm's discriminator reads it. */
        private var stableDiscarded: Long = 0L

        /**
         * The bound on STABLE membership divergence, carried over from
         * `GcSafetySweepTest.MAX_STABLE_DIVERGING` unchanged. It is a CEILING on a known failure
         * mode on a non-reproducible rig, not a pin on the current number — see that constant's
         * KDoc for the measurement history and for why it is not read off the control arm at
         * runtime.
         */
        private const val MAX_STABLE_DIVERGING: Int = 12

        private val CLASSIFIED: Set<String> = setOf(
            OrMapGcSafetySweep.RESURRECTION_FAILURE,
            OrMapGcSafetySweep.MEMBERSHIP_DIVERGENCE_FAILURE,
            OrMapGcSafetySweep.FENCE_ATTRIBUTED_DIVERGENCE_FAILURE,
            OrMapGcSafetySweep.VALUE_DIVERGENCE_FAILURE,
            OrMapGcSafetySweep.VALUE_FOLD_DRIFT_FAILURE,
            OrMapGcSafetySweep.DISAGREEMENT_FAILURE,
        )

        private val checks: MutableMap<OrMapGcSafetySweep.Trigger, DstCheck> = mutableMapOf()

        @JvmStatic
        @BeforeAll
        fun register() {
            OrMapGcSafetySweep.Trigger.entries.forEach {
                GraphRegistry.register(OrMapGcSafetySweep.graph(it))
                checks[it] = OrMapGcSafetySweep.check(it)
                OrMapGcSafetySweep.totals.getValue(it).reset()
            }
            stableRoot.deleteRecursively()
            noneRoot.deleteRecursively()
            localRoot.deleteRecursively()
        }

        @JvmStatic
        @AfterAll
        fun unregister() {
            OrMapGcSafetySweep.Trigger.entries.forEach {
                GraphRegistry.unregister(it.id)
                CheckRegistry.unregister(it.checkId)
            }
        }
    }
}
