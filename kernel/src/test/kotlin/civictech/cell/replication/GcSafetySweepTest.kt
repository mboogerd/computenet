package civictech.cell.replication

import civictech.cell.data.SetCell
import civictech.cell.data.delta.SetDelta
import civictech.testkit.dst.CheckRegistry
import civictech.testkit.dst.DepartEvent
import civictech.testkit.dst.DepartureMode
import civictech.testkit.dst.DstCheck
import civictech.testkit.dst.DstRun
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
import org.junit.jupiter.api.Test
import java.io.File
import java.util.WeakHashMap
import kotlin.test.assertTrue

// ================================================================================================
// computenet-9sm.4.4 — the GC safety sweep. See [GcSafetySweep] for the model.
// ================================================================================================

/** One breach of a per-invocation compaction rule, recorded in the hook and thrown by the check. */
internal data class GcViolation(
    val kind: String,
    val step: Int,
    val peer: String,
    val detail: String,
) {
    override fun toString(): String = "$kind step=$step peer=$peer $detail"
}

/**
 * What one run's step hooks recorded.
 *
 * Violations are **recorded, never thrown**: an exception out of a `StepHooks` hook propagates out
 * of `DstRun.execute()` and is a broken experiment rather than a FAILED verdict. The registered
 * [DstCheck] is what throws, on a quiesced run. (Same rule, same reason, as
 * `StableFrontierChurnSweep`.)
 */
internal class GcObservations {
    val violations: MutableList<GcViolation> = mutableListOf()

    /** Removals actually issued by the removes hook (`MeshPeer.remove` returned true). */
    var removesIssued: Long = 0

    /** `compactBelow` calls made. */
    var invocations: Long = 0

    /** Tags discarded across all invocations — the reclaimer's own work. */
    var discarded: Long = 0

    /** `(peer)` evaluations of the `resurrected(...)` observable at quiescence. */
    var resurrectionChecks: Long = 0
}

internal object GcObservationRegistry {
    private val byWorld = WeakHashMap<DstWorld, GcObservations>()

    @Synchronized
    fun of(world: DstWorld): GcObservations = byWorld.getOrPut(world) { GcObservations() }
}

/** Sweep-wide non-vacuity counters for ONE trigger, absorbed from each quiesced run. */
internal class GcTotals(val label: String) {
    var runs: Int = 0
    var invocations: Long = 0
    var discarded: Long = 0
    var resurrectionChecks: Long = 0

    /** Removals issued, per run — the per-seed non-vacuity assertion needs the minimum. */
    val removesPerRun: MutableList<Long> = mutableListOf()

    fun reset() {
        runs = 0
        invocations = 0
        discarded = 0
        resurrectionChecks = 0
        removesPerRun.clear()
    }

    @Synchronized
    fun absorb(observations: GcObservations) {
        runs++
        invocations += observations.invocations
        discarded += observations.discarded
        resurrectionChecks += observations.resurrectionChecks
        removesPerRun += observations.removesIssued
    }

    override fun toString(): String =
        "$label{runs=$runs invocations=$invocations discarded=$discarded " +
            "resurrectionChecks=$resurrectionChecks removes=${removesPerRun.sum()} " +
            "minRemovesOnASeed=${removesPerRun.minOrNull() ?: -1}}"
}

/**
 * BS-12 (`[KE3-23]`) and BS-13 (`[KE3-20]`) as ONE seeded sweep run twice: a reclaimer driving
 * [SetCell.compactBelow] from `Replication.stableFrontier` (STABLE), and the same reclaimer driven
 * from the wrong seam `Replication.localDeliveredFrontier` (LOCAL), over the CHA1/CHA3 churn rig
 * with partition, heal, churn and duplicate/reorder faults folded in.
 *
 * ## The adversary is the sibling sweep's, deliberately
 *
 * [StableFrontierChurnSweep.config] and [StableFrontierChurnSweep.churnPlan] are reused rather
 * than re-derived, so this sweep and BS-5's run over the *same* generated churn and the two are
 * comparable like for like. `churnPlan` already heals dangling partitions, so a still-suspended
 * peer is rejoined before quiescence. No `CrashFault` is folded in here: the out-of-band crash has
 * no paired rejoin, so the crashed peer's frozen fold would fail `converged()` for a reason that
 * has nothing to do with compaction (churn's own `CRASH_UNCLEAN`, always paired with a rejoin by
 * `ChurnGenerator`, still covers crash).
 *
 * ## The observable
 *
 * `resurrected(cell, fold) = cell.membership() − MeshConvergences.project(fold).elements`, reused
 * verbatim from `CompactionTriggerPinTest`, whose class KDoc defines it. It is the only observable
 * that can see a re-admission: `ReplicaConvergence` folds EMITTED deltas and keeps every tombstone
 * the cell ever emitted, while `compactBelow` drops them from the cell.
 *
 * ## What the deterministic pins already settled, and what this sweep therefore asks
 *
 * `CompactionTriggerPinTest` (computenet-9sm.4.2) MEASURED that `SetCell.foldDelivered` is fed only
 * from `add()` and from `applyRemote()`'s `newAdds` — `remove()` mints and folds nothing — so the
 * stable frontier certifies ADD delivery only, and a straggler that missed a REMOVE can resurrect
 * the element even under STABLE. That is settled deterministically and is not re-litigated here.
 * This sweep asks the SEEDED question instead: does the churn adversary reach that state on its
 * own, and how often, under partition, duplicate and reorder — and does the wrong seam (LOCAL)
 * reach it strictly more often.
 *
 * ## Honesty (feature rule 6, harness half)
 *
 * This is a **bounded-schedule check over a finite seed range**, not a proof. It says nothing about
 * schedules outside the range or outside this generator's reach; the universally-quantified form
 * needs FRM1's model checker. The residual is filed by the docs task (computenet-9sm.4.5) in
 * `concord/corpus/DISPUTES.md` under the entry title
 * **"GC safety under compaction is bounded-schedule evidence, not a proof (`[KE3-20]`, `[KE3-23]`)"**.
 */
object GcSafetySweep {

    enum class Trigger(val id: String, val checkId: String) {
        STABLE("gc-safety-sweep-stable", "gc-safety-stable"),
        LOCAL("gc-safety-sweep-local", "gc-safety-local"),
    }

    // The sibling's constants are private to its file; restated here (9sm.4.4's own record).
    private const val PEER_COUNT: Int = 3
    private const val OP_SCRIPT_LENGTH: Int = 24
    private const val STEP_BUDGET: Int = 6000
    private const val DRAIN_MARGIN: Int = 1000
    private const val WRITE_START: Int = 300
    private const val WRITE_STRIDE: Int = 200

    /**
     * Steps between an add and its paired remove. ESTIMATED: under the 200-step stride, so an add
     * and its remove straddle at most a few compaction points, and past the "tens of steps" one
     * replicated write costs.
     */
    private const val REMOVE_LAG: Int = 90

    /** Compaction period, in controller steps. ESTIMATED per 9sm.4-D4. */
    private const val K: Int = 25

    val faultIds: Set<String> = setOf("gc-park", "gc-dup", "gc-reorder")

    /** Sizes the graph only: roster length and the strided write schedule. */
    private val templatePlan: ChurnPlan =
        ChurnSeeds.plans(0L..0L, StableFrontierChurnSweep.config).single().let { plan ->
            plan.copy(
                writeSchedule = (0 until OP_SCRIPT_LENGTH).map { i ->
                    ChurnWrite(WRITE_START + i * WRITE_STRIDE, plan.peers[i % plan.peers.size], i)
                },
            )
        }

    /** The removes the removes-hook will issue: every odd-ordinal write, [REMOVE_LAG] steps later. */
    private val removeSchedule: Map<Int, List<Pair<String, String>>> =
        templatePlan.writeSchedule
            .filter { it.ordinal % 2 == 1 }
            .groupBy({ it.atStep + REMOVE_LAG }, { it.peer to "${it.peer}-${it.ordinal}" })

    internal val totals: Map<Trigger, GcTotals> = Trigger.entries.associateWith { GcTotals(it.name) }

    fun graphOf(trigger: Trigger): GraphSpec = GraphSpec(trigger.id) { world ->
        ChurnMesh.spec(
            templatePlan,
            payload = MeshPayload.SET,
            maxPeers = PEER_COUNT,
            aliveUntil = STEP_BUDGET + DRAIN_MARGIN,
        ).builder.build(world)
        // Installed INSIDE the builder so every seed's freshly-built world carries both hooks.
        world.steps.onStep { w, step -> issueRemoves(w, step) }
        world.steps.onStep { w, step -> compact(w, step, trigger) }
    }

    private val stableGraph: GraphSpec by lazy { graphOf(Trigger.STABLE) }
    private val localGraph: GraphSpec by lazy { graphOf(Trigger.LOCAL) }

    fun graph(trigger: Trigger): GraphSpec = if (trigger == Trigger.STABLE) stableGraph else localGraph

    // ----------------------------------------------------------------------------- the workload

    /**
     * The remover is the adder, so the removal is effective — a removal of an element the replica
     * has not observed is a no-op in `SetCell`.
     */
    private fun issueRemoves(world: DstWorld, step: Int) {
        val due = removeSchedule[step] ?: return
        val observations = GcObservationRegistry.of(world)
        for ((peer, element) in due) {
            if (MeshPeers.find(world, peer)?.remove(element) == true) observations.removesIssued++
        }
    }

    // ---------------------------------------------------------------------------- the reclaimer

    @Suppress("UNCHECKED_CAST")
    private fun compact(world: DstWorld, step: Int, trigger: Trigger) {
        if (step <= 0 || step % K != 0) return
        val observations = GcObservationRegistry.of(world)
        for (peer in MeshPeers.all(world)) {
            if (!peer.member) continue
            val cell = (peer.replica ?: continue) as? SetCell<String> ?: continue
            val frontier = when (trigger) {
                Trigger.STABLE -> peer.replication.stableFrontier(peer.ref.id)
                Trigger.LOCAL -> peer.replication.localDeliveredFrontier(peer.ref.id)
            }
            val before = cell.membership()
            val discarded = cell.compactBelow(frontier)
            val after = cell.membership()

            observations.invocations++
            observations.discarded += discarded
            // Feature rule 3: reclamation is invisible to the value.
            if (after != before) {
                observations.violations += GcViolation(
                    "compaction changed membership", step, peer.name,
                    "added=${after - before} removed=${before - after} discarded=$discarded",
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
        PartitionFault.park("gc-park", "peer0<->peer1", from = 1200, until = 1800),
        DuplicateFault.frames("gc-dup", "peer1<->peer2", copies = 1, probability = 0.5),
        ReorderFault("gc-reorder", "peer0<->peer2", window = 3),
    ).toFaultPlan()

    // --------------------------------------------------------------------------------- the check

    /**
     * `membership() − project(emitted-delta fold)` — `CompactionTriggerPinTest`'s observable,
     * verbatim. An element in this set is live in the cell while the cell's own emitted history
     * says it was removed: a re-admission.
     */
    @Suppress("UNCHECKED_CAST")
    private fun resurrected(cell: SetCell<String>, fold: SetDelta<String>): Set<String> =
        cell.membership() - (MeshConvergences.project(fold) as ReferenceFold.Elements).elements

    const val VIOLATION_FAILURE: String = "compaction broke a per-invocation rule"
    const val RESURRECTION_FAILURE: String = "compaction resurrected a removed element"
    const val DISAGREEMENT_FAILURE: String = "live folds disagree after compaction"

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
        for (peer in live) {
            val cell = peer.replica as? SetCell<String> ?: continue
            val fold = MeshConvergences.of(world, peer.name)?.state(peer.ref) as? SetDelta<String> ?: continue
            observations.resurrectionChecks++
            val re = resurrected(cell, fold)
            if (re.isNotEmpty()) {
                val tagView = re.associateWith { e ->
                    "adds=${fold.adds[e]?.map { it.counter }?.sorted()} " +
                        "dels=${fold.dels[e]?.map { it.counter }?.sorted()}"
                }
                resurrections += "peer=${peer.name} elements=$re $tagView"
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

        val disagreeing = MeshPeers.all(world).mapNotNull { peer ->
            val convergence = MeshConvergences.of(world, peer.name) ?: return@mapNotNull null
            if (convergence.converged()) null else peer.name to convergence.states().keys
        }
        totals.getValue(trigger).absorb(observations)
        if (disagreeing.isNotEmpty()) {
            // The second datum is a DIAGNOSTIC LABEL, not a check: memberships agreeing while the
            // folds differ is branch F-A — `ReplicaConvergence` cannot express compaction (a
            // replica that (re)joins after a peer compacted receives a catch-up without the
            // discarded tags), and the feature forbids substituting a weaker bespoke check.
            val memberships = live.mapNotNull { (it.replica as? SetCell<String>)?.membership() }.toSet()
            throw ChurnCheckFailure(
                DISAGREEMENT_FAILURE,
                detail = "peers with unconverged folds: ${disagreeing.joinToString { "${it.first}${it.second}" }}; " +
                    "membershipsAgree=${memberships.size <= 1} memberships=$memberships",
            )
        }
    }
}

/**
 * BS-12 / BS-13. See [GcSafetySweep] for the model, the observable, and the honesty clause; this
 * class is the seed range, the two runs, and the non-vacuity accounting.
 */
class GcSafetySweepTest {

    @Test
    fun `compaction at the stable frontier is GC-safe across a churn sweep_BS12`() {
        val startedAt = System.nanoTime()
        val sweep = MeshConvergences.observing {
            dstSweep(
                suite = "gc-safety-stable",
                seeds = SEEDS,
                graph = GcSafetySweep.graph(GcSafetySweep.Trigger.STABLE),
                checkId = GcSafetySweep.Trigger.STABLE.checkId,
                budget = 200_000,
                artifactRoot = stableRoot,
                planFor = GcSafetySweep::plan,
            )
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        val totals = GcSafetySweep.totals.getValue(GcSafetySweep.Trigger.STABLE)

        stableResurrecting = sweep.failures.filter { it.message == GcSafetySweep.RESURRECTION_FAILURE }
            .map { it.seed }.toSet()
        stableDisagreeing = sweep.failures.filter { it.message == GcSafetySweep.DISAGREEMENT_FAILURE }
            .map { it.seed }.toSet()
        val other = sweep.failures.filterNot {
            it.message == GcSafetySweep.RESURRECTION_FAILURE || it.message == GcSafetySweep.DISAGREEMENT_FAILURE
        }

        // The seed range is RECORDED and NEVER narrowed after a failure: a red seed is reported
        // with its artifact path (below), not replaced by a friendlier one.
        println(
            "[BS-12] seeds=$SEEDS elapsedMs=$elapsedMs artifacts=$stableRoot totals=$totals " +
                "${sweep.summary()}\n" +
                "[BS-12] F-B resurrecting seeds=$stableResurrecting (classified F-B)\n" +
                "[BS-12] F-A fold-disagreeing seeds=$stableDisagreeing (classified F-A: " +
                "ReplicaConvergence cannot express compaction)\n" +
                "[BS-12] artifacts=${sweep.artifactPaths}",
        )
        assertTrue(
            other.isEmpty(),
            "unclassified STABLE failures — every failure must be F-A or F-B, or the sweep is " +
                "reporting something this task has not accounted for: " +
                other.joinToString { "${it.seed}:${it.message}" },
        )
        assertNonVacuous("BS-12", sweep.total, totals)
        assertAdversaryFired("BS-12", sweep)
    }

    @Test
    fun `compaction at the local delivered frontier resurrects a removed element_BS13`() {
        val startedAt = System.nanoTime()
        val sweep = MeshConvergences.observing {
            dstSweep(
                suite = "gc-safety-local",
                seeds = SEEDS,
                graph = GcSafetySweep.graph(GcSafetySweep.Trigger.LOCAL),
                checkId = GcSafetySweep.Trigger.LOCAL.checkId,
                budget = 200_000,
                artifactRoot = localRoot,
                planFor = GcSafetySweep::plan,
            )
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        val totals = GcSafetySweep.totals.getValue(GcSafetySweep.Trigger.LOCAL)

        val resurrecting = sweep.failures.filter { it.message == GcSafetySweep.RESURRECTION_FAILURE }
            .map { it.seed }.toSet()
        println(
            "[BS-13] seeds=$SEEDS elapsedMs=$elapsedMs artifacts=$localRoot totals=$totals " +
                "${sweep.summary()}\n" +
                "[BS-13] resurrecting seeds=$resurrecting\n" +
                "[COMPARISON] STABLE: resurrecting seeds = $stableResurrecting, " +
                "fold-disagreeing seeds = $stableDisagreeing; LOCAL: resurrecting seeds = $resurrecting",
        )
        assertNonVacuous("BS-13", sweep.total, totals)
        assertAdversaryFired("BS-13", sweep)

        // The control that passes by OBSERVING the failure: the wrong seam must be able to make
        // the observable fire, or the observable is inert and BS-12's green arm proves nothing.
        assertTrue(
            resurrecting.isNotEmpty(),
            "[KE3-20]: no seed in $SEEDS resurrected a removed element under the LOCAL trigger. " +
                "That is the 9sm.4-D3 finding — widen the adversary, never weaken the check — and " +
                "[KE3-20] stays open. failures=${sweep.failures.map { it.seed to it.message }}",
        )
        assertTrue(
            BS13_SEED in resurrecting,
            "the recorded BS13_SEED=$BS13_SEED must still resurrect; it is never replaced by a " +
                "friendlier seed. resurrecting=$resurrecting",
        )
    }

    private fun assertNonVacuous(tag: String, total: Int, totals: GcTotals) {
        assertTrue(totals.runs == total, "$tag: every seed must have absorbed its counters: $totals of $total")
        assertTrue(totals.invocations > 0, "$tag: the reclaimer never ran: $totals")
        assertTrue(
            totals.discarded > 0,
            "$tag: a sweep whose reclaimer never discarded a tag proves nothing about reclamation: $totals",
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

    private fun assertAdversaryFired(tag: String, sweep: civictech.testkit.dst.DstSweepReport) {
        val drawnModes = mutableSetOf<DepartureMode>()
        sweep.entries.forEach { entry ->
            val plan = StableFrontierChurnSweep.churnPlan(entry.seed)
            val fired = entry.report?.appliedFaults.orEmpty().filter { it.fired > 0 }.map { it.id }.toSet()
            val planned = plan.events.map { it.id }.toSet() + GcSafetySweep.faultIds
            assertTrue(
                planned.all { it in fired },
                "$tag seed ${entry.seed}: every planned churn event and folded fault must fire, or the " +
                    "adversary proves nothing; missing=${planned - fired} fired=$fired",
            )
            plan.events.filterIsInstance<DepartEvent>().forEach { drawnModes += it.mode }
        }
        assertTrue(
            drawnModes.containsAll(DepartureMode.entries),
            "$tag: the sweep must draw every departure mode across its range: drawn=$drawnModes",
        )
    }

    @Test
    fun `the recorded BS13 seed reproduces digest-for-digest on the local graph_BS13`() {
        MeshConvergences.observing {
            DstRun(
                GcSafetySweep.graph(GcSafetySweep.Trigger.LOCAL),
                GcSafetySweep.plan(BS13_SEED),
                40_000,
                checks.getValue(GcSafetySweep.Trigger.LOCAL),
            ).assertDeterministic()
        }
    }

    companion object {
        /**
         * Recorded, and **never narrowed after a failure**. Sized by MEASUREMENT of this sweep's
         * own wall time (printed by both arms); see the bd comment on computenet-9sm.4.4.
         */
        private val SEEDS = 1L..30L

        /**
         * The FIRST seed observed to resurrect under the LOCAL trigger, recorded by number so the
         * control is a pin and not a search. Never replaced by a friendlier seed.
         */
        private const val BS13_SEED: Long = 1L

        private val stableRoot = File("build/dst-stability/gc-sweep-stable")
        private val localRoot = File("build/dst-stability/gc-sweep-local")

        /** Set by the BS-12 arm, read by BS-13's comparison line. */
        private var stableResurrecting: Set<Long> = emptySet()
        private var stableDisagreeing: Set<Long> = emptySet()

        /** Registered once in [register]; a second `CheckRegistry.register` of the same id would clash. */
        private val checks: MutableMap<GcSafetySweep.Trigger, DstCheck> = mutableMapOf()

        @JvmStatic
        @BeforeAll
        fun register() {
            GcSafetySweep.Trigger.entries.forEach {
                GraphRegistry.register(GcSafetySweep.graph(it))
                checks[it] = GcSafetySweep.check(it)
                GcSafetySweep.totals.getValue(it).reset()
            }
            stableRoot.deleteRecursively()
            localRoot.deleteRecursively()
        }

        @JvmStatic
        @AfterAll
        fun unregister() {
            GcSafetySweep.Trigger.entries.forEach {
                GraphRegistry.unregister(it.id)
                CheckRegistry.unregister(it.checkId)
            }
        }
    }
}
