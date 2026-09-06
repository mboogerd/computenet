package civictech.cell.replication

import civictech.cell.data.WatermarkCell
import civictech.testkit.dst.CheckRegistry
import civictech.testkit.dst.ChurnEvent
import civictech.testkit.dst.CrashFault
import civictech.testkit.dst.DepartEvent
import civictech.testkit.dst.DepartureMode
import civictech.testkit.dst.DstCheck
import civictech.testkit.dst.DstWorld
import civictech.testkit.dst.DuplicateFault
import civictech.testkit.dst.FaultPlan
import civictech.testkit.dst.GraphRegistry
import civictech.testkit.dst.GraphSpec
import civictech.testkit.dst.JoinEvent
import civictech.testkit.dst.PartitionFault
import civictech.testkit.dst.RejoinEvent
import civictech.testkit.dst.ReorderFault
import civictech.testkit.dst.churn.ChurnCheckFailure
import civictech.testkit.dst.churn.ChurnConfig
import civictech.testkit.dst.churn.ChurnMesh
import civictech.testkit.dst.churn.ChurnPlan
import civictech.testkit.dst.churn.ChurnSeeds
import civictech.testkit.dst.churn.ChurnWrite
import civictech.testkit.dst.churn.MeshConvergences
import civictech.testkit.dst.churn.MeshPayload
import civictech.testkit.dst.churn.MeshPeers
import civictech.testkit.dst.dstSweep
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID
import java.util.WeakHashMap
import kotlin.test.assertTrue

// ================================================================================================
// Per-world observation state. A DstCheck registered in CheckRegistry is a VALUE resolved by id,
// and a sweep builds one world per seed — so everything a step hook records is keyed weakly by
// DstWorld, the idiom `doc/dst-rig.md` §4 prescribes and `EffectLogs`/`ExclusiveLedgers` use.
// ================================================================================================

/** One `[KE3-17]` breach: a stable value ahead of an open member's delivered row. */
internal data class StabilityViolation(
    val step: Int,
    val peer: String,
    val slot: UUID,
    val source: UUID,
    val stable: Long,
    val row: Long,
) {
    override fun toString(): String =
        "step=$step peer=$peer slot=$slot source=$source stable=$stable openRow=" +
            (if (row == Long.MIN_VALUE) "absent(bottom)" else "$row")
}

/** One `[KE3-18]` breach: a source's stable value dropped while the open-slot set held still. */
internal data class StabilityRegression(
    val step: Int,
    val peer: String,
    val source: UUID,
    val before: Long,
    val after: Long,
) {
    override fun toString(): String = "step=$step peer=$peer source=$source $before -> $after"
}

/**
 * What one run's step hook recorded.
 *
 * Violations are **recorded, never thrown**: an exception out of a `StepHooks` hook propagates
 * out of `DstRun.execute()` and is a broken experiment rather than a FAILED verdict
 * (`DstWorld.StepHooks`), which would deny the sweep the artifact and the density it exists to
 * produce. The registered [DstCheck] is what throws, on a quiesced run.
 */
internal class StabilityObservations {
    val violations: MutableList<StabilityViolation> = mutableListOf()
    val regressions: MutableList<StabilityRegression> = mutableListOf()

    /** (peer, step) pairs on which a companion existed and the frontier was read at all. */
    var frontierReads: Long = 0

    /** …of which the frontier was NON-EMPTY against a non-empty open set — the load-bearing count. */
    var nonEmptyFrontierReads: Long = 0

    /** Individual `(peer, slot, source)` comparisons actually evaluated. */
    var triplesChecked: Long = 0

    /**
     * `(peer, source)` pairs on which `[KE3-18]`'s arm actually ran — that is, a source present in
     * BOTH this read and the previous one for that peer, with the open-slot set unchanged between
     * them. Counted because the exemption clause is the arm's own vacuity risk: a sweep in which
     * membership never held still between two reads would satisfy `[KE3-18]` without ever
     * comparing anything.
     */
    var regressionComparisons: Long = 0

    /** Per peer: the open-slot set and the frontier as of the previous step it was read on. */
    val previous: MutableMap<String, Pair<Set<UUID>, Map<UUID, Long>>> = mutableMapOf()
}

internal object StabilityObservationRegistry {
    private val byWorld = WeakHashMap<DstWorld, StabilityObservations>()

    @Synchronized
    fun of(world: DstWorld): StabilityObservations = byWorld.getOrPut(world) { StabilityObservations() }
}

/** Sweep-wide non-vacuity counters, absorbed from each quiesced run's observations. */
internal object StabilityTotals {
    var frontierReads: Long = 0
    var nonEmptyFrontierReads: Long = 0
    var triplesChecked: Long = 0
    var regressionComparisons: Long = 0
    var runs: Int = 0

    fun reset() {
        frontierReads = 0
        nonEmptyFrontierReads = 0
        triplesChecked = 0
        regressionComparisons = 0
        runs = 0
    }

    @Synchronized
    fun absorb(observations: StabilityObservations) {
        frontierReads += observations.frontierReads
        nonEmptyFrontierReads += observations.nonEmptyFrontierReads
        triplesChecked += observations.triplesChecked
        regressionComparisons += observations.regressionComparisons
        runs++
    }

    override fun toString(): String =
        "runs=$runs frontierReads=$frontierReads nonEmptyFrontierReads=$nonEmptyFrontierReads " +
            "triplesChecked=$triplesChecked regressionComparisons=$regressionComparisons"
}

/**
 * BS-5 / `[KE3-17]` / `[KE3-18]` at sweep scale: `Replication.stableFrontier` never runs ahead of
 * any open member's delivered row, at **every** controller step of **every** seed of a
 * `ChurnSeeds`-derived churn sweep with the CHA1 adversary folded in.
 *
 * ## The oracle is the kernel read, not a harness probe
 *
 * Every assertion below reads `MeshPeer.replication.stableFrontier(...)` — the facade over
 * `civictech.cell.consistency.CausalStability`, which is the MIN. `testkit`'s
 * `StabilityObservables.stabilityCovers` is deliberately **not** used: it is a harness-side
 * per-wave coverage probe mirroring `MemberDepartureFrontierTest`'s lambda, not a MIN and not the
 * production read, so a sweep asserting on it would prove something about the harness.
 *
 * ## The FU-2 hold, and why `[KE3-18]` is conditioned on the open-slot set
 *
 * `CausalStability.stableFrontier`'s open set unions `instancesOf` with the companion's announced
 * `members()`, and a slot known only through the announcement has no row yet — so it drags every
 * source to bottom and the frontier legitimately SHRINKS the moment membership grows. That dip is
 * the documented FU-2 asymmetry in its conservative direction, not a regression, so `[KE3-18]` is
 * asserted only **between consecutive reads whose open-slot set is unchanged**. A membership
 * change exempts the step it lands on.
 *
 * ## Graph and per-seed plan
 *
 * One `GraphSpec` for the whole range, as `dstSweep` requires — the roster is pinned
 * (`peerCount = 3..3`, so `peer0..peer2` every seed) and the write schedule is hand-strided
 * across the horizon, both for the reasons `ChurnReconvergenceSweep`'s KDoc records
 * (`ChurnGenerator` packs writes into the first ~`opScriptLength` steps, and
 * `ChurnPlan.toFaultPlan` never carries a write schedule into a `FaultPlan` anyway). What varies
 * per seed is the churn — and the CHA1 faults ride along on the same plan.
 */
object StableFrontierChurnSweep {

    const val ID: String = "stable-frontier-churn-sweep"
    const val CHECK_ID: String = "stable-frontier-never-exceeds-open-row"

    private const val PEER_COUNT: Int = 3
    private const val EVENT_COUNT: Int = 6
    private const val OP_SCRIPT_LENGTH: Int = 24
    private const val STEP_BUDGET: Int = 6000

    /** Slack after the last generated event, so gossip drains before quiescence — see `ChurnSweepTest`. */
    private const val DRAIN_MARGIN: Int = 1000

    private const val WRITE_START: Int = 300
    private const val WRITE_STRIDE: Int = 200

    val config: ChurnConfig = ChurnConfig(
        peerCount = PEER_COUNT..PEER_COUNT,
        eventCount = EVENT_COUNT,
        opScriptLength = OP_SCRIPT_LENGTH,
        writeConcurrency = 0.3,
        partitionOverlap = 0.3,
        stepBudget = STEP_BUDGET,
        suspendWindow = 60,
    )

    /** Sizes the graph only: roster length and the strided write schedule. */
    private val templatePlan: ChurnPlan = ChurnSeeds.plans(0L..0L, config).single().let { plan ->
        plan.copy(
            writeSchedule = (0 until OP_SCRIPT_LENGTH).map { i ->
                ChurnWrite(WRITE_START + i * WRITE_STRIDE, plan.peers[i % plan.peers.size], i)
            },
        )
    }

    val graph: GraphSpec = GraphSpec(ID) { world ->
        ChurnMesh.spec(
            templatePlan,
            payload = MeshPayload.SET,
            maxPeers = PEER_COUNT,
            aliveUntil = STEP_BUDGET + DRAIN_MARGIN,
        ).builder.build(world)
        // Installed INSIDE the builder so every seed's freshly-built world carries it.
        world.steps.onStep { w, step -> observe(w, step) }
    }

    // -------------------------------------------------------------------------- the CHA1 adversary

    /**
     * Windows chosen inside `[WRITE_START, STEP_BUDGET)` so every fault overlaps live writes
     * (the strided schedule spans steps 300..4900).
     */
    private const val PARK_FROM: Int = 1200
    private const val PARK_UNTIL: Int = 1800

    /** The peer `cha1-crash` takes down. See [crashStepFor] for why the step cannot be a constant. */
    private const val CRASH_PEER: String = "peer2"

    /** The last step the strided write schedule reaches — the end of the window a fault can overlap. */
    private const val LAST_WRITE_STEP: Int = WRITE_START + (OP_SCRIPT_LENGTH - 1) * WRITE_STRIDE

    val faultIds: Set<String> = setOf("cha1-park", "cha1-dup", "cha1-reorder", "cha1-crash")

    fun churnPlan(seed: Long): ChurnPlan =
        healDanglingPartitions(ChurnSeeds.plans(seed..seed, config).single())

    /**
     * **A substitution against the bead's prescribed `CrashFault.midDrain("cha1-crash", "peer2",
     * atStep)` with a fixed step, and the reason is measured, not theoretical.**
     *
     * A CHA1 host-plane crash is invisible to the churn mesh's own bookkeeping: `HostSlot.crash()`
     * discards the host and rebuilds it empty, leaving `MeshPeer.replica` null while
     * `MeshPeer.member` stays true. `MeshPeer.evict` then throws
     * `IllegalStateException: peer "peer2" holds no replica, so an eviction cannot be applied to
     * it` the next time a generated `EVICT_CLEAN`/`EVICT_NO_CLOSE` departure lands on that peer —
     * out of a step hook, so it propagates out of `DstRun.execute()` as a BROKEN EXPERIMENT rather
     * than a property failure. `ChurnGenerator`'s coherence promise never produces that pairing on
     * its own (a `CRASH_UNCLEAN` departure is always followed by a rejoin before anything else acts
     * on the peer); folding an out-of-band crash in at a fixed step does. Measured on the first run
     * of this sweep: `atStep = 2500` broke **11 of 30 seeds** that way, all with the message above.
     *
     * So the step is derived per seed instead: two steps past [CRASH_PEER]'s LAST membership event,
     * which is the earliest point at which no later mesh event can act on the crashed peer, floored
     * at [WRITE_START] so it never precedes the workload. The property is unchanged — a host-plane
     * crash of an open member, folded into the same run as the churn — and the fault still fires on
     * every seed (asserted). What is lost on the seeds whose derived step exceeds
     * [LAST_WRITE_STEP] is the crash's overlap with *scheduled writes*; it still lands inside the
     * gossip drain, with deltas in flight. The per-seed step and that overlap are printed by the
     * test so the loss is visible rather than assumed.
     */
    fun crashStepFor(plan: ChurnPlan): Int {
        val lastOnPeer = plan.events.filter { it.peer == CRASH_PEER }.maxOfOrNull { it.atStep } ?: 0
        return maxOf(lastOnPeer + 2, WRITE_START).coerceAtMost(STEP_BUDGET - 1)
    }

    /** True when this seed's derived crash step still lands inside the write schedule's span. */
    fun crashOverlapsWrites(plan: ChurnPlan): Boolean = crashStepFor(plan) <= LAST_WRITE_STEP

    fun plan(seed: Long): FaultPlan = churnPlan(seed).let { churn ->
        churn.withFaults(
            PartitionFault.park("cha1-park", "peer0<->peer1", from = PARK_FROM, until = PARK_UNTIL),
            DuplicateFault.frames("cha1-dup", "peer1<->peer2", copies = 1, probability = 0.5),
            ReorderFault("cha1-reorder", "peer0<->peer2", window = 3),
            CrashFault.midDrain("cha1-crash", CRASH_PEER, atStep = crashStepFor(churn)),
        ).toFaultPlan()
    }

    /**
     * `ChurnReconvergenceSweep.healDanglingPartitions`, copied rather than called: it is `private`
     * in a `:testkit` **test** source file and therefore not on `:kernel`'s classpath at all, and
     * this task may not edit any `testkit` file to widen it. Copying is the honest option and is
     * recorded as such.
     *
     * A peer whose last drawn event is a `PARTITION_SUSPEND` stays suspended for the rest of the
     * run; `MeshPeer.partitionAway` never clears `member`, so its row stays open forever and
     * freezes the MIN. That is the *correct* shape of an unhealed partition and not something
     * this sweep is asking about, so any still-suspended peer gets one appended `RejoinEvent`
     * just past the last generated step.
     */
    private fun healDanglingPartitions(plan: ChurnPlan): ChurnPlan {
        val suspended = linkedSetOf<String>()
        for (event in plan.events) {
            when (event) {
                is JoinEvent -> suspended -= event.peer
                is RejoinEvent -> suspended -= event.peer
                is DepartEvent -> if (event.mode == DepartureMode.PARTITION_SUSPEND) {
                    suspended += event.peer
                } else {
                    suspended -= event.peer
                }
                else -> Unit
            }
        }
        if (suspended.isEmpty()) return plan
        val lastStep = plan.events.maxOfOrNull { it.atStep } ?: 0
        val healStep = (lastStep + 1).coerceAtMost(plan.stepBudget - 1)
        val heals: List<ChurnEvent> = suspended.map { peer -> RejoinEvent("heal-$peer", peer, healStep) }
        return plan.copy(events = plan.events + heals)
    }

    // ------------------------------------------------------------------------ the per-step oracle

    /**
     * `[KE3-17]`, per live peer, per open slot, per source — plus `[KE3-18]`'s fixed-membership
     * regression tracking.
     *
     * The open-slot set is re-derived here, independently of `CausalStability`, from exactly the
     * two reads spec 42 §"The stability read" names: this peer's `replicasOf` mapped through
     * `WatermarkCell.slotId(watermarkRef(...))`, unioned with the companion's announced
     * `members()`, minus `closed()`. `degrade = false`, so a PN-19 *suspended* row stays open —
     * the WAIT reading, which is the conservative one and the one BS-9/BS-10 want.
     *
     * A peer whose companion is absent is skipped rather than failed: a crash rebuild replaces
     * `MeshPeer.replication` with a fresh instance, so `watermarkOf` is legitimately null until
     * the replica is re-spawned.
     */
    private fun observe(world: DstWorld, step: Int) {
        val observations = StabilityObservationRegistry.of(world)
        for (peer in MeshPeers.all(world)) {
            val replication = peer.replication
            val companion = replication.watermarkOf(peer.ref.id) ?: continue
            val rows = companion.rows()
            val open = buildSet {
                peer.registry.replicasOf(peer.ref.id).mapTo(this) {
                    WatermarkCell.slotId(replication.watermarkRef(it))
                }
                addAll(companion.members())
                removeAll(companion.closed())
            }
            val stable = replication.stableFrontier(peer.ref.id).perSource

            observations.frontierReads++
            if (stable.isNotEmpty() && open.isNotEmpty()) observations.nonEmptyFrontierReads++

            for ((source, value) in stable) {
                for (slot in open) {
                    observations.triplesChecked++
                    val row = rows[slot]?.get(source) ?: Long.MIN_VALUE
                    if (value > row) {
                        observations.violations += StabilityViolation(step, peer.name, slot, source, value, row)
                    }
                }
            }

            val previous = observations.previous[peer.name]
            if (previous != null && previous.first == open) {
                for ((source, value) in stable) {
                    val before = previous.second[source] ?: continue
                    observations.regressionComparisons++
                    if (value < before) {
                        observations.regressions += StabilityRegression(step, peer.name, source, before, value)
                    }
                }
            }
            observations.previous[peer.name] = open to stable
        }
    }

    /**
     * The registered check: fixed identities in the message, everything run-varying in the
     * `ChurnCheckFailure` detail (`doc/dst-rig.md` §3), so `FailurePredicate.sameFailingCheck`
     * still recognises a shrunk reproduction.
     */
    fun check(): DstCheck = CheckRegistry.register(CHECK_ID) { world ->
        val observations = StabilityObservationRegistry.of(world)
        StabilityTotals.absorb(observations)
        observations.violations.firstOrNull()?.let { first ->
            throw ChurnCheckFailure(
                "stableFrontier exceeded an open member's delivered row",
                detail = "${observations.violations.size} violation(s); first: $first; " +
                    "reads=${observations.frontierReads} nonEmpty=${observations.nonEmptyFrontierReads} " +
                    "triples=${observations.triplesChecked}",
            )
        }
        observations.regressions.firstOrNull()?.let { first ->
            throw ChurnCheckFailure(
                "stableFrontier regressed under fixed membership",
                detail = "${observations.regressions.size} regression(s); first: $first; " +
                    "reads=${observations.frontierReads} nonEmpty=${observations.nonEmptyFrontierReads}",
            )
        }
    }
}

/**
 * BS-5 at sweep scale. See [StableFrontierChurnSweep] for the model; this class is the seed range,
 * the run, and the non-vacuity accounting.
 */
class StableFrontierChurnSweepTest {

    @Test
    fun `stableFrontier never exceeds an open member's delivered row across a churn sweep_BS5`() {
        val startedAt = System.nanoTime()
        val sweep = MeshConvergences.observing {
            dstSweep(
                suite = "stable-frontier-churn",
                seeds = SEEDS,
                graph = StableFrontierChurnSweep.graph,
                checkId = StableFrontierChurnSweep.CHECK_ID,
                budget = 40_000,
                artifactRoot = root,
                planFor = StableFrontierChurnSweep::plan,
            )
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        // The seed range is RECORDED, never narrowed: a failing seed is reported with its
        // artifact path (assertAllPassed prints both), not replaced by a friendlier one.
        println(
            "[BS-5] seeds=$SEEDS elapsedMs=$elapsedMs artifacts=$root " +
                "totals=$StabilityTotals ${sweep.summary()}",
        )
        sweep.assertAllPassed()

        // ------------------------------------------------------------------ the adversary fired
        val drawnModes = mutableSetOf<DepartureMode>()
        sweep.entries.forEach { entry ->
            val plan = StableFrontierChurnSweep.churnPlan(entry.seed)
            val fired = entry.report?.appliedFaults.orEmpty().filter { it.fired > 0 }.map { it.id }.toSet()
            val planned = plan.events.map { it.id }.toSet() + StableFrontierChurnSweep.faultIds
            assertTrue(
                planned.all { it in fired },
                "seed ${entry.seed}: every planned churn event and every folded CHA1 fault must fire, " +
                    "or the adversary this sweep claims proves nothing; missing=${planned - fired} fired=$fired",
            )
            plan.events.filterIsInstance<DepartEvent>().forEach { drawnModes += it.mode }
        }
        val overlapping = sweep.entries.count {
            StableFrontierChurnSweep.crashOverlapsWrites(StableFrontierChurnSweep.churnPlan(it.seed))
        }
        println(
            "[BS-5] crash steps: " +
                sweep.entries.joinToString { e ->
                    "${e.seed}=${StableFrontierChurnSweep.crashStepFor(StableFrontierChurnSweep.churnPlan(e.seed))}"
                } +
                " — $overlapping of ${sweep.total} land inside the write schedule",
        )
        assertTrue(
            drawnModes.containsAll(DepartureMode.entries),
            "the sweep must draw every departure mode across its range, or the adversary is narrower " +
                "than the config claims: drawn=$drawnModes",
        )

        // ------------------------------------------------------------------ the oracle ran on real state
        assertTrue(
            StabilityTotals.nonEmptyFrontierReads > 0,
            "a sweep whose stableFrontier was always empty checked nothing: $StabilityTotals",
        )
        assertTrue(
            StabilityTotals.triplesChecked > 0,
            "no (peer, slot, source) triple was ever compared: $StabilityTotals",
        )
        assertTrue(
            StabilityTotals.regressionComparisons > 0,
            "[KE3-18]'s arm never ran: no source was ever present in two consecutive reads under an " +
                "unchanged open-slot set, so the no-drop property was never actually compared: $StabilityTotals",
        )
    }

    companion object {
        /**
         * Recorded per the acceptance criterion, and **never narrowed after a failure**: a red seed
         * is reported with its artifact path under [root]. Sized by measurement, not estimate —
         * see the bd comment on computenet-9sm.3.4 for the wall time this range costs.
         */
        private val SEEDS = 1L..60L
        private val root = File("build/dst-stability/churn-sweep")

        @JvmStatic
        @BeforeAll
        fun register() {
            GraphRegistry.register(StableFrontierChurnSweep.graph)
            StableFrontierChurnSweep.check()
            StabilityTotals.reset()
            root.deleteRecursively()
        }

        @JvmStatic
        @AfterAll
        fun unregister() {
            GraphRegistry.unregister(StableFrontierChurnSweep.ID)
            CheckRegistry.unregister(StableFrontierChurnSweep.CHECK_ID)
        }
    }
}
