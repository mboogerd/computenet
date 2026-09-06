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
 * `CompactionTriggerPinTest` (computenet-9sm.4.2) MEASURED that, before computenet-v2ka's del-dot,
 * `SetCell.foldDelivered` was fed only from `add()` and from `applyRemote()`'s `newAdds` —
 * `remove()` minted and folded nothing — so the stable frontier certified ADD delivery only, and a
 * straggler that missed a REMOVE could resurrect the element even under STABLE. computenet-v2ka
 * closed that half: `remove()` now mints a del-dot into its `dels` entry and `applyRemote` folds the
 * del lane too, so the stable frontier now certifies the REMOVE as well (`SetCell.remove`'s KDoc).
 * What is still settled deterministically and not re-litigated here is that this leaves the
 * RE-ADMISSION half open — a duplicated or reordered frame re-delivering a tag the reclaimer already
 * discarded (`concord/corpus/DISPUTES.md` `## KE3-GC-DEL-LANE`). This sweep asks the SEEDED question:
 * does the churn adversary reach that re-admission state on its own, and how often, under partition,
 * duplicate and reorder — and does the wrong seam (LOCAL) reach it strictly more often.
 *
 * ## What was MEASURED (seeds 1..200, budget 40_000, 16-core macOS; ~5.0 s + ~4.3 s)
 *
 * **BS-12 is branch F, and its F-B arm is the headline result: compaction at the STABLE frontier
 * resurrects removed elements too.** `doc/kernel-lane-findings.md` `## KE3-GC` records six
 * independent 200-seed STABLE sweeps — 8, 10, 10, 11 (implementer) and 9, 12 (reviewer) — so the
 * band measured so far is 8-12 resurrecting seeds, and 122-126 fold-disagreeing (F-A) seeds. The
 * LOCAL arm's same six runs found 8, 11, 12, 12, 14 and 15. So `[KE3-20]` is reproduced — and the
 * feature's empirical claim that LOCAL's set is a
 * **strict superset** of STABLE's is **falsified**: the two sets overlap without either containing
 * the other. That is consistent with `CompactionTriggerPinTest`'s P2 mechanism rather than
 * surprising given it — the stable frontier certifies ADD delivery only, so it is not the
 * qualitatively safer seam a superset relation would imply. It is a *seeded* corroboration of a
 * deterministically settled fact, at rates this range can see.
 *
 * ## The rig is not reproducible on a healed partition, and that is not this task's doing
 *
 * `DstRun.assertDeterministic()` does not fail on every churn-mesh configuration tried — it fails
 * only on a plan containing a `DepartureMode.PARTITION_SUSPEND` departure that is later rejoined or
 * healed (`doc/dst-rig.md` §"A peering that re-opens mid-run is outside the determinism contract",
 * computenet-l0gd): `Peering.Loopback.heal()`'s announcement sweep re-runs over hash-ordered maps
 * keyed by `UUID.randomUUID()`, so the re-announcement order is a fresh draw every run. A plan
 * drawing only `EVICT_CLEAN`/`EVICT_NO_CLOSE`/`CRASH_UNCLEAN` departures IS trace-reproducible. See
 * the pin test's KDoc for the 2x2 corner measurement that first localized the cause on this graph,
 * and for what is pinned instead. Filed separately; nothing here is weakened to accommodate it.
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

        /**
         * **The no-reclaimer control** (computenet-v2ka). Same graph, same workload, same
         * adversary, same checks — and the reclaimer never fires. It exists to answer the one
         * question the other two arms cannot: is a failure this sweep reports a property of
         * COMPACTION, or of the churn rig underneath it?
         *
         * It was added while a per-source re-admission floor was on the branch (built, measured
         * unsafe, reverted in 5bfc85b91), where the cross-replica membership check
         * ([MEMBERSHIP_DIVERGENCE_FAILURE]) fired on ~36 of 200 STABLE seeds and a nearly
         * identical ~39 under LOCAL. Those two figures are from THAT build, not this one: on the
         * shipped tree STABLE diverges on ~2 and the control on ~2-4. A failure class that does
         * not discriminate between the
         * right seam and the wrong one is evidence about the rig, not about the trigger — and
         * asserting it empty on the STABLE arm would have failed good work for a defect it did
         * not cause. This arm measures that baseline instead of guessing at it.
         */
        NONE("gc-safety-sweep-none", "gc-safety-none"),
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

    /**
     * The last step the reclaimer fires on — the mesh's own `aliveUntil` horizon, past which the
     * heartbeat has stopped and the run is supposed to be draining towards quiescence.
     *
     * **MEASURED, not stylistic.** An UNBOUNDED reclaimer makes the run non-terminating whenever
     * removals are also issued: `compactBelow` "records nothing" (its KDoc) and deliberately
     * re-admits a straggler carrying a discarded tag, so a compaction point that discards a
     * tombstone lets the next gossip frame re-deliver that tag as novel, the cell emits, the next
     * compaction point 25 steps later discards it again, and the world never goes idle. It is a
     * property of the pair: with seeds 1..10 at budget 40_000, removes-only and reclaimer-only
     * each exhausted 0 of 10, both-unbounded exhausted 6 of 10, and both-with-this-bound exhausted
     * 0 of 10. Raising the budget does not help — the sweep at 200_000 exhausted the same 19 of 30
     * as at 40_000 (bd comment on computenet-9sm.4.4). A reclaimer that runs for ever after the
     * workload has stopped is not the thing under test; one that runs across the whole workload
     * and its drain is.
     *
     * It does **not** blunt the observable: every compaction point inside the workload still
     * fires, and a re-admission that happened there is still live in `membership()` at quiescence,
     * which is where `resurrected(...)` reads it.
     */
    private const val RECLAIM_UNTIL: Int = STEP_BUDGET + DRAIN_MARGIN

    val faultIds: Set<String> =
        setOf("gc-park", "gc-park-b", "gc-park-c", "gc-dup", "gc-reorder")

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
        world.steps.onStep { w, step -> if (step <= RECLAIM_UNTIL) compact(w, step, trigger) }
    }

    private val graphs: Map<Trigger, GraphSpec> by lazy { Trigger.entries.associateWith { graphOf(it) } }

    fun graph(trigger: Trigger): GraphSpec = graphs.getValue(trigger)

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
        // The control arm never reclaims — see [Trigger.NONE].
        if (trigger == Trigger.NONE) return
        val observations = GcObservationRegistry.of(world)
        for (peer in MeshPeers.all(world)) {
            if (!peer.member) continue
            val cell = (peer.replica ?: continue) as? SetCell<String> ?: continue
            val frontier = when (trigger) {
                Trigger.STABLE -> peer.replication.stableFrontier(peer.ref.id)
                Trigger.LOCAL -> peer.replication.localDeliveredFrontier(peer.ref.id)
                Trigger.NONE -> return // unreachable: the control returned above
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
        // computenet-nwnl: THE WIDENED ADVERSARY — the response `[KE3-20]`'s own failure message
        // prescribes ("widen the adversary, never weaken the check").
        //
        // ## What was too thin, and what these two add
        //
        // `gc-park` alone parks ONE of the mesh's three links for 600 of the run's ~7000 steps.
        // With [WRITE_START] 300, [WRITE_STRIDE] 200 and [REMOVE_LAG] 90 the odd-ordinal removes
        // land at 590, 990, 1390 ... 4990, so that one window encloses exactly TWO of the twelve
        // removes, on one link, once per run. The wrong seam therefore got two chances per seed
        // to be caught reclaiming something the mesh had not acked; the other ten removes ran
        // against an unimpeded mesh where `localDeliveredFrontier` and `stableFrontier` are only
        // a few steps apart. That thinness is why the LOCAL arm's harm fell to 1-3 of 200 once
        // computenet-vhlm removed the tag-counter collision that had been inflating it.
        //
        // These two park the OTHER two links, in their own windows, so all three links are
        // exercised and the enclosed removes go from two to six (ordinals 11 and 13 at 2590 and
        // 2990 for `gc-park-b`, 17 and 19 at 3790 and 4190 for `gc-park-c`).
        //
        // ## Why the windows are DISJOINT, which is the load-bearing part
        //
        // The obvious stronger adversary — park both of one peer's links over one window, so it
        // is genuinely severed — was built and MEASURED and is REJECTED, because it destroys the
        // very distinction this sweep exists to measure. [LinkControl.severing] is what
        // `ChurnMesh` declares per pair, and severing un-mirrors each side's MEMBERSHIP entry for
        // the duration (`ControlSeams`' own KDoc says so). An isolated peer is therefore not a
        // straggler the others are still waiting on: it is a NON-MEMBER, so `stableFrontier`
        // stops requiring its ack and advances exactly as `localDeliveredFrontier` does. The two
        // seams become the same seam. Measured on this host, 2026-09-06, seeds 1..200, four runs
        // with `peer2` severed from both neighbours over 2400..3000: the LOCAL arm's harm rose
        // (7, 1, 4, 3 of 200, all fence-attributed) and the STABLE arm went fence-attributed on 1
        // of the 4 runs (seed 12, `peer2-23`) — i.e. the widening made the RIGHT seam look wrong
        // too. A sharper adversary that blunts the discriminator is not a sharper adversary.
        //
        // Disjoint windows keep the third link up throughout, so every peer always has a relay
        // path, membership is never un-mirrored, and `stableFrontier` still means what it says.
        PartitionFault.park("gc-park-b", "peer0<->peer2", from = 2400, until = 3000),
        PartitionFault.park("gc-park-c", "peer1<->peer2", from = 3600, until = 4200),
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

    /**
     * **The observable `resurrected(...)` cannot see** (computenet-v2ka, raised on this bead by
     * the computenet-9sm.6 breakdown): live replicas whose MEMBERSHIPS disagree at quiescence.
     *
     * `resurrected(cell, fold)` compares a cell against its OWN emitted fold, so a schedule in
     * which the tombstone-holders reclaim and a straggler keeps the element live reads as
     * `{}` at *every* replica — A and C are fenced and empty, and B's own fold carries the add
     * with no del, so B agrees with itself. The mesh has permanently diverged and the check is
     * silent. That is not hypothetical: with the re-admission fence in place the LOCAL arm's
     * resurrections vanish and this is what replaces them, which is precisely the "affordable
     * measurement standing in for the property" trap — a green branch-G reading obtained on
     * evidence that cannot see the failure.
     *
     * So this is a STRONGER check inside the existing harness, not a weaker bespoke one:
     * `converged()` is still asserted (as [DISAGREEMENT_FAILURE], the tolerated F-A class),
     * and this sits *above* it, catching the divergences F-A's tolerance would otherwise hide.
     */
    const val MEMBERSHIP_DIVERGENCE_FAILURE: String = "live replicas' memberships diverge after compaction"

    /**
     * The strictly stronger half of [MEMBERSHIP_DIVERGENCE_FAILURE] (computenet-vhlm): a
     * divergence the RE-ADMISSION FENCE ITSELF caused, established by a direct read of
     * `SetCell`'s `ReclaimedDots` rather than by an inference about the workload.
     *
     * ## Why this class exists
     *
     * computenet-pay7's acceptance asked that the STABLE arm's divergence be "no worse than the
     * Trigger.NONE control arm's, measured in the same run". It is not, in COUNT — the fence
     * measures ~3x the control (see [GcSafetySweepTest.MAX_STABLE_DIVERGING]'s KDoc for the
     * amendment and its measurement). The argument that the excess is nevertheless not the
     * fence's harm was an ordinal-parity inference: [GcSafetySweep.removeSchedule] removes only
     * ODD-ordinal writes, so an EVEN-ordinal element's add-tag never enters a `dels` entry,
     * `compactBelow` can never record it, and it is structurally un-fenceable. That argument is
     * true and checkable, but it is a statement about the RIG, and it says nothing at all about
     * the odd-ordinal elements that DO diverge.
     *
     * This check replaces it with the measurement. At quiescence, for every element the live
     * replicas disagree on, it takes the tags that make the element live at the replicas that
     * HOLD it (`SetCell.liveTagsOf`) and asks every replica that LACKS it whether any of those
     * tags is in its own fence (`SetCell.fencedAmong`). A hit means that replica cannot ever
     * admit the element — the frame carrying it is inadmissible by construction — so the
     * divergence is the fence's, and the repair emission that exists to prevent exactly this
     * did not reach the straggler. No hit means the fence is not why the memberships differ.
     *
     * It is a SEPARATE failure message, not a strengthening of the old one, so that the two
     * populations stay countable against each other in one run: `MEMBERSHIP_DIVERGENCE_FAILURE`
     * keeps measuring the rig's late-write floor (the CONTROL arm's own class), and this one
     * measures the fence. The STABLE arm asserts this is EMPTY; the divergence count keeps its
     * own, unchanged, absolute bound.
     */
    const val FENCE_ATTRIBUTED_DIVERGENCE_FAILURE: String =
        "a diverging element's live tag is in the lacking replica's ReclaimedDots"

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

        // The cross-replica membership check — see [MEMBERSHIP_DIVERGENCE_FAILURE]. It runs
        // BEFORE the fold check so a genuinely diverged mesh is diagnosed as such rather than
        // absorbed into the tolerated F-A class.
        val cellsByPeer = live.mapNotNull { peer ->
            (peer.replica as? SetCell<String>)?.let { peer.name to it }
        }
        val membershipsByPeer = cellsByPeer.map { (name, cell) -> name to cell.membership() }
        if (membershipsByPeer.map { it.second }.distinct().size > 1) {
            totals.getValue(trigger).absorb(observations)
            // THE ATTRIBUTION READ (computenet-vhlm) — see [FENCE_ATTRIBUTED_DIVERGENCE_FAILURE].
            // Per element the live replicas disagree on: the tags that make it live where it IS
            // live, checked against the fence of every replica where it is NOT. This is a direct
            // read of `ReclaimedDots`, not an inference from the remove schedule's ordinal parity.
            val union = membershipsByPeer.flatMap { it.second }.toSet()
            val agreed = membershipsByPeer.map { it.second }.reduce { a, b -> a intersect b }
            val differing = union - agreed
            val attributions = differing.map { element ->
                val holders = membershipsByPeer.filter { element in it.second }.map { it.first }
                val liveTags = cellsByPeer
                    .filter { it.first in holders }
                    .flatMap { it.second.liveTagsOf(element) }
                    .toSet()
                val fencedAt = cellsByPeer
                    .filterNot { it.first in holders }
                    .mapNotNull { (name, cell) ->
                        val fenced = cell.fencedAmong(element, liveTags)
                        if (fenced.isEmpty()) null else {
                            name to (fenced.map { it.counter }.sorted() to (fenced.size == liveTags.size))
                        }
                    }
                // computenet-dwkp's MEASUREMENT. `fencedAtLacking` says a lacking replica holds
                // the live tag in its fence; it does not say whether the tag was minted by the
                // incarnation now fencing it or by an earlier one — and that distinction is the
                // whole of computenet-vhlm's recorded same-element residual. `SetCell.
                // fenceProvenance` reads it directly (incarnation ordinal, restore count, and
                // the element THIS instance minted that counter for), and the peer's own
                // `lastDeparture` says whether it ever left. Printed for every fenced tag so a
                // sweep that catches the rare schedule carries the answer in its artifact rather
                // than needing to be caught again.
                val provenance = cellsByPeer
                    .filterNot { it.first in holders }
                    .flatMap { (name, cell) ->
                        cell.fencedAmong(element, liveTags).sortedBy { it.counter }.map { t ->
                            val peer = live.firstOrNull { it.name == name }
                            "$name{${cell.fenceProvenance(element, t)} " +
                                "lastDeparture=${peer?.lastDeparture} suspended=${peer?.suspended}}"
                        }
                    }
                Triple(element, "$element held=$holders liveTags=${liveTags.map { it.counter }.sorted()} " +
                    (if (provenance.isEmpty()) "" else "provenance=$provenance ") +
                    "fencedAtLacking=" + (
                    if (fencedAt.isEmpty()) "NONE"
                    else fencedAt.joinToString { "${it.first}:${it.second.first}${if (it.second.second) "(all)" else "(partial)"}" }
                    ),
                    fencedAt.isNotEmpty())
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
        // Reset HERE, not only in @BeforeAll: the determinism arm runs the LOCAL check too, and
        // `runs == total` must count this sweep's runs and nothing else.
        GcSafetySweep.totals.getValue(GcSafetySweep.Trigger.STABLE).reset()
        val startedAt = System.nanoTime()
        val sweep = MeshConvergences.observing {
            dstSweep(
                suite = "gc-safety-stable",
                seeds = SEEDS,
                graph = GcSafetySweep.graph(GcSafetySweep.Trigger.STABLE),
                checkId = GcSafetySweep.Trigger.STABLE.checkId,
                budget = BUDGET,
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
        stableDiverging = sweep.failures.filter { it.message == GcSafetySweep.MEMBERSHIP_DIVERGENCE_FAILURE }
            .map { it.seed }.toSet()
        stableFenceAttributed = sweep.failures
            .filter { it.message == GcSafetySweep.FENCE_ATTRIBUTED_DIVERGENCE_FAILURE }
            .map { it.seed }.toSet()
        val other = sweep.failures.filterNot { it.message in CLASSIFIED }

        // The seed range is RECORDED and NEVER narrowed after a failure: a red seed is reported
        // with its artifact path (below), not replaced by a friendlier one.
        println(
            "[BS-12] seeds=$SEEDS elapsedMs=$elapsedMs artifacts=$stableRoot totals=$totals " +
                "${sweep.summary()}\n" +
                "[BS-12] F-B resurrecting seeds=$stableResurrecting (classified F-B)\n" +
            // The detail carries the peer, the elements, and their add/del tag counters — the
            // only thing that says WHICH mechanism resurrected. Without it a resurrecting seed
            // is a bare number and the next reader re-derives it from scratch (computenet-v2ka).
            sweep.failures.filter { it.message == GcSafetySweep.RESURRECTION_FAILURE }
                .joinToString("") { e ->
                    "[BS-12] F-B seed=${e.seed} " +
                        "${(e.cause as? ChurnCheckFailure)?.detail ?: e.cause?.suppressed?.firstOrNull()}\n"
                } +
                "[BS-12] F-A fold-disagreeing seeds=$stableDisagreeing (classified F-A: " +
                "ReplicaConvergence cannot express compaction)\n" +
            "[BS-12] membership-diverging seeds=$stableDiverging\n" +
            sweep.failures.filter { it.message == GcSafetySweep.MEMBERSHIP_DIVERGENCE_FAILURE }
                .joinToString("") { e ->
                    "[BS-12] DIVERGE seed=${e.seed} ${(e.cause as? ChurnCheckFailure)?.detail}\n"
                } +
                // computenet-vhlm: the attribution read. Every DIVERGE line above carries an
                // `attribution=[...]` clause naming, per differing element, the tags that make it
                // live and whether any replica LACKING it has one of those tags fenced. This list
                // is the seeds where one did — the fence's own harm, and it must be empty.
                "[BS-12] FENCE-ATTRIBUTED diverging seeds=$stableFenceAttributed\n" +
                sweep.failures.filter { it.message == GcSafetySweep.FENCE_ATTRIBUTED_DIVERGENCE_FAILURE }
                    .joinToString("") { e ->
                        "[BS-12] FENCED-DIVERGE seed=${e.seed} ${(e.cause as? ChurnCheckFailure)?.detail}\n"
                    } +
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

        // BRANCH G. `[KE3-23]`'s hazard had TWO halves. computenet-v2ka closed the
        // DEL-DELIVERY half (the del-dot: `SetCell.remove` mints a dot into its `dels` entry,
        // `applyRemote` folds the del lane into the delivered frontier, and `[KE3-31]`'s
        // every-tag rule then certifies the REMOVE and not merely the add). computenet-pay7
        // closes the RE-ADMISSION half — `[24-TAG-04]` clause 2 — with a causal-context fence:
        // `compactBelow` records the exact dots it discarded in `SetCell`'s `ReclaimedDots`,
        // `applyRemote` subtracts that set from the novelty it computes on both lanes, and a
        // fenced add-tag is answered with a minimal tombstone naming exactly that tag so the
        // straggler that still holds it live drops it instead of diverging.
        //
        // MEASURED on base `b1180c935`, 16-core macOS under load, seeds 1..200, budget 40_000:
        //
        //   before the fence (del-dot only)  F-B on 6 seeds [110, 117, 126, 144, 168, 197]
        //                                    membership-diverging 3, CONTROL diverging 2
        //   + fence, no repair emission      F-B on 0 seeds, membership-diverging **30**
        //   + fence + repair emission        F-B on 0 seeds, membership-diverging 5-8 across
        //                                    four 200-seed runs, CONTROL 1-4 in the same runs
        //
        // The middle row is why the repair emission exists and is recorded here rather than
        // discovered again: a SILENT fence does not remove the resurrection, it converts it
        // into a permanent divergence, at the same order (30) as the per-source floor's 31-33.
        // Only `MEMBERSHIP_DIVERGENCE_FAILURE` can see that, which is the whole reason
        // computenet-v2ka added it.
        //
        // THIS ASSERTION IS THE FLIP the bead required, not a deletion: it asserted
        // `stableResurrecting.isNotEmpty()` and now asserts the opposite.
        assertTrue(
            stableResurrecting.isEmpty(),
            "[KE3-23] branch G: compaction at the STABLE frontier must resurrect NOTHING now " +
                "that the re-admission fence is in place. A hit here means a duplicated or " +
                "reordered frame re-delivered a tag `compactBelow` had discarded and " +
                "`applyRemote` re-admitted it — read the detail line's `adds=[n] dels=[n, n+1]` " +
                "shape, and check `ReclaimedDots` before assuming the rig changed. Do not " +
                "narrow SEEDS to reach it. resurrecting=$stableResurrecting failures=" +
                sweep.failures.map { it.seed to it.message },
        )
        // THE ATTRIBUTION ASSERTION (computenet-vhlm) — the one that carries computenet-pay7's
        // criterion 2 now that its "no worse in COUNT than the control" half has been amended
        // (see [MAX_STABLE_DIVERGING]'s KDoc for the amendment, its measurement, host and date).
        //
        // It is strictly stronger than the count bound below on the question the criterion was
        // actually about — "does reclamation cost membership convergence?" — because it names a
        // MECHANISM instead of a number: every element the live replicas disagree on is checked,
        // by a direct read of `ReclaimedDots`, against every replica that lacks it. A seed here
        // is a replica that can never admit the element, whatever is re-delivered to it, which is
        // exactly the silent-fence failure the repair emission exists to prevent (measured at 30
        // of 200 before that emission — see [GcSafetySweep.FENCE_ATTRIBUTED_DIVERGENCE_FAILURE]).
        //
        // It replaces the ordinal-parity INFERENCE that stood here. That inference is still true
        // and still recorded, but it could only ever exonerate the even-ordinal elements, and it
        // is the odd-ordinal ones (`peer2-23` in the feature review's run) that the fence could
        // in principle touch. This assertion covers both, on every seed, in the same run.
        assertTrue(
            stableFenceAttributed.isEmpty(),
            "[KE3-23] the re-admission fence CAUSED a membership divergence: on these seeds a " +
                "live replica is missing an element whose live tag is in that replica's own " +
                "`ReclaimedDots`, so no re-delivery can ever admit it and the repair emission " +
                "did not reach the straggler that still holds it. This is the silent-fence " +
                "failure, not the rig's late-write floor — read the FENCED-DIVERGE detail lines' " +
                "`attribution=` clause. Do not absorb it into MAX_STABLE_DIVERGING. seeds=" +
                "$stableFenceAttributed",
        )
        // The divergence bound is read against the no-reclaimer CONTROL arm, not against zero:
        // the rig diverges on a few seeds with no compaction at all. What this asserts is that
        // compaction does not make it MATERIALLY worse — which is exactly the assertion that
        // fails for a per-source re-admission floor (31-33 of 200 against a floor of 2-4), and
        // the reason this bead did not ship one. See SetCell.compactBelow's KDoc.
        assertTrue(
            // computenet-vhlm reads the bound against BOTH divergence classes. Splitting the
            // attributed sub-class out must not loosen the number that was recorded before the
            // split: a seed that moves from one class to the other is still a diverged seed.
            (stableDiverging + stableFenceAttributed).size <= MAX_STABLE_DIVERGING,
            "[KE3-23]: compacting at the STABLE frontier left " +
                "${(stableDiverging + stableFenceAttributed).size} seeds with " +
                "permanently diverged memberships, above the recorded bound of " +
                "$MAX_STABLE_DIVERGING. Reclamation must not cost membership convergence — a " +
                "resurrection-only observable reports this state as GREEN. diverging=" +
                "$stableDiverging fenceAttributed=$stableFenceAttributed " +
                "(compare the CONTROL arm, which measures the rig's own floor)",
        )
        // F-A is unchanged and still expected: `ReplicaConvergence` folds emitted deltas and
        // cannot express compaction, so its disagreement is a limit of the reference fold, not of
        // the system. It stays a tolerated class rather than a silent one.
        assertTrue(
            stableDisagreeing.isNotEmpty(),
            "[KE3-23] branch F-A: no fold disagreement was observed, which the recorded measurement " +
                "says should happen on well over half the range: $stableDisagreeing",
        )
    }

    /**
     * **The no-reclaimer baseline** (computenet-v2ka). See [GcSafetySweep.Trigger.NONE]: the same
     * graph, workload and adversary with the reclaimer switched off, so every other arm's failure
     * counts can be read against a floor rather than against zero.
     *
     * It asserts only what it can honestly promise — that the sweep ran, that the adversary
     * fired, and that NOTHING is resurrected when nothing is reclaimed (a resurrection here
     * would mean the observable itself is broken, since `resurrected(...)` compares a cell to
     * its own emitted fold and no tombstone was ever dropped). The membership-divergence count
     * it measures is RECORDED, not asserted against a number: it is a property of the churn rig,
     * and pinning it would make an unrelated rig change fail this bead's item.
     */
    @Test
    fun `the no-reclaimer control measures the rig's own divergence floor_BS12`() {
        GcSafetySweep.totals.getValue(GcSafetySweep.Trigger.NONE).reset()
        val sweep = MeshConvergences.observing {
            dstSweep(
                suite = "gc-safety-none",
                seeds = SEEDS,
                graph = GcSafetySweep.graph(GcSafetySweep.Trigger.NONE),
                checkId = GcSafetySweep.Trigger.NONE.checkId,
                budget = BUDGET,
                artifactRoot = noneRoot,
                planFor = GcSafetySweep::plan,
            )
        }
        controlResurrecting = sweep.failures.filter { it.message == GcSafetySweep.RESURRECTION_FAILURE }
            .map { it.seed }.toSet()
        controlDiverging = sweep.failures.filter { it.message == GcSafetySweep.MEMBERSHIP_DIVERGENCE_FAILURE }
            .map { it.seed }.toSet()
        val other = sweep.failures.filterNot { it.message in CLASSIFIED }
        println(
            "[CONTROL] seeds=$SEEDS ${sweep.summary()}\n" +
                "[CONTROL] resurrecting seeds=$controlResurrecting\n" +
                "[CONTROL] membership-diverging seeds=$controlDiverging " +
                "(${controlDiverging.size} of ${sweep.total}) — the RIG's own floor, recorded not pinned\n" +
                // computenet-vhlm: the CONTROL arm prints its per-seed DIVERGE detail too. It
                // always computed them — the same check builds them — but only its seed LIST was
                // printed, so the STABLE arm's claim that its excess divergences are "the same
                // late-write straggler shape the CONTROL arm itself produces" had no artifact
                // behind it on this side. Now both arms emit the same line and the shapes are
                // comparable in one run. Recorded, never asserted: see this test's KDoc.
                sweep.failures.filter { it.message == GcSafetySweep.MEMBERSHIP_DIVERGENCE_FAILURE }
                    .joinToString("") { e ->
                        "[CONTROL] DIVERGE seed=${e.seed} ${(e.cause as? ChurnCheckFailure)?.detail}\n"
                    } +
                "[CONTROL] artifacts=${sweep.artifactPaths}",
        )
        // A run that reclaims nothing writes nothing into `ReclaimedDots`, so the attribution
        // read must be structurally silent here. This is the CONTROL for the attribution check
        // itself (computenet-vhlm): a hit would mean the read is reporting something other than
        // the fence.
        assertTrue(
            sweep.failures.none { it.message == GcSafetySweep.FENCE_ATTRIBUTED_DIVERGENCE_FAILURE },
            "[KE3-23] control: nothing is reclaimed here, so no diverging element's live tag can " +
                "be in any replica's `ReclaimedDots`. A hit means the attribution read is wrong, " +
                "not the system: " +
                sweep.failures.filter { it.message == GcSafetySweep.FENCE_ATTRIBUTED_DIVERGENCE_FAILURE }
                    .map { it.seed },
        )
        assertTrue(other.isEmpty(), "unclassified control failures: ${other.joinToString { "${it.seed}:${it.message}" }}")
        assertTrue(
            controlResurrecting.isEmpty(),
            "[KE3-23] control: a run that reclaims NOTHING cannot resurrect anything — a hit here " +
                "means the observable is broken, not the system: $controlResurrecting",
        )
        assertAdversaryFired("CONTROL", sweep)
    }

    @Test
    fun `compaction at the local delivered frontier resurrects a removed element_BS13`() {
        GcSafetySweep.totals.getValue(GcSafetySweep.Trigger.LOCAL).reset()
        val startedAt = System.nanoTime()
        val sweep = MeshConvergences.observing {
            dstSweep(
                suite = "gc-safety-local",
                seeds = SEEDS,
                graph = GcSafetySweep.graph(GcSafetySweep.Trigger.LOCAL),
                checkId = GcSafetySweep.Trigger.LOCAL.checkId,
                budget = BUDGET,
                artifactRoot = localRoot,
                planFor = GcSafetySweep::plan,
            )
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        val totals = GcSafetySweep.totals.getValue(GcSafetySweep.Trigger.LOCAL)

        val resurrecting = sweep.failures.filter { it.message == GcSafetySweep.RESURRECTION_FAILURE }
            .map { it.seed }.toSet()
        val disagreeing = sweep.failures.filter { it.message == GcSafetySweep.DISAGREEMENT_FAILURE }
            .map { it.seed }.toSet()
        // computenet-vhlm: BOTH divergence classes. The LOCAL arm is the WRONG seam by design, so
        // a fence-attributed divergence here is expected harm, not a defect — but it must still
        // count towards the harm this arm asserts, or splitting the class would let the arm read
        // GREEN on a strictly worse outcome than the one it was recorded for.
        val diverging = sweep.failures.filter {
            it.message == GcSafetySweep.MEMBERSHIP_DIVERGENCE_FAILURE ||
                it.message == GcSafetySweep.FENCE_ATTRIBUTED_DIVERGENCE_FAILURE
        }.map { it.seed }.toSet()
        val other = sweep.failures.filterNot { it.message in CLASSIFIED }
        val fenceAttributed = sweep.failures
            .filter { it.message == GcSafetySweep.FENCE_ATTRIBUTED_DIVERGENCE_FAILURE }
            .map { it.seed }.toSet()
        println(
            "[BS-13] membership-diverging seeds=$diverging (${diverging.size} of ${sweep.total}); " +
                "of which fence-attributed=$fenceAttributed; per-seed LOCAL pin: $BS13_PIN_RETIRED",
        )
        println(
            "[BS-13] seeds=$SEEDS elapsedMs=$elapsedMs artifacts=$localRoot totals=$totals " +
                "${sweep.summary()}\n" +
                "[BS-13] resurrecting seeds=$resurrecting (${resurrecting.size} of ${sweep.total})\n" +
                "[BS-13] fold-disagreeing seeds=$disagreeing (${disagreeing.size} of ${sweep.total})\n" +
                "[COMPARISON] STABLE: resurrecting seeds = $stableResurrecting, " +
                "fold-disagreeing seeds = $stableDisagreeing; LOCAL: resurrecting seeds = $resurrecting",
        )
        assertTrue(
            other.isEmpty(),
            "unclassified LOCAL failures: ${other.joinToString { "${it.seed}:${it.message}" }}",
        )
        assertNonVacuous("BS-13", sweep.total, totals)
        assertAdversaryFired("BS-13", sweep)

        // The control that passes by OBSERVING the failure: the wrong seam must be able to make
        // the observable fire, or the observable is inert and BS-12's arm proves nothing.
        //
        // **The control asserts the UNION of the two harm classes, and the widening is
        // deliberate — read it as forward cover, not as a description of the shipped tree.** On
        // THIS tree LOCAL still RESURRECTS (measured 12 of 200 at head; the pre-v2ka band was
        // 8-15), so the `resurrecting` half alone is what currently fires and the union costs
        // nothing today. What the union buys is the case computenet-v2ka MEASURED on a build
        // that is NOT in this tree: with a per-source re-admission floor in place (built,
        // measured unsafe, reverted in 5bfc85b91 — see `SetCell.compactBelow`'s KDoc) LOCAL's
        // resurrections vanish and are replaced by permanent membership DIVERGENCE — the
        // tombstone-holders reclaim a del the straggler never delivered, the straggler keeps the
        // element live, and no path repairs it. `resurrected(...)` reads `{}` at every replica
        // in that state (each cell agrees with its OWN fold), which is why
        // [GcSafetySweep.MEMBERSHIP_DIVERGENCE_FAILURE] had to be added to this harness at all.
        // Asserting only the old form would silently retire `[KE3-20]`'s control the day a fence
        // lands. A clean run — neither class — still fails this assertion, which is the property
        // the control exists to hold.
        assertTrue(
            (resurrecting + diverging).isNotEmpty(),
            "[KE3-20]: no seed in $SEEDS was observably harmed by compacting at the LOCAL " +
                "delivered frontier — neither a resurrection nor a membership divergence. That " +
                "would be the 9sm.4-D3 finding — widen the adversary, never weaken the check — " +
                "and [KE3-20] would stay open. failures=${sweep.failures.map { it.seed to it.message }}",
        )

        // ------------------------------------------------------------------ computenet-nwnl
        // THE SWEEP-LEVEL DISCRIMINATOR, promoted from a diagnostic to an assertion, and the
        // REPLACEMENT for the per-seed LOCAL pin that used to live in
        // `the recorded seeds reproduce their verdict_BS12_BS13`. See [BS13_PIN_RETIRED] for
        // the provenance, the measurement, and why a per-seed pin is no longer honest here.
        //
        // What it says is the wrong seam's harm in the sharpest form this rig can state: the
        // LOCAL arm produces divergences the FENCE ITSELF caused — established by a direct read
        // of `ReclaimedDots`, not inferred — while the STABLE arm asserts the same class EMPTY
        // (see the BS-12 arm's `stableFenceAttributed` assertion). One trigger, one adversary,
        // one seed range, opposite verdicts: that IS `[KE3-20]`.
        //
        // It is STRICTLY STRONGER than the assertion above it, which it does not replace: that
        // one accepts any harm, including a rig-floor divergence the CONTROL arm also produces.
        // This one accepts only harm attributable to compacting below a frontier that certifies
        // nothing about other replicas.
        assertTrue(
            fenceAttributed.isNotEmpty(),
            "[KE3-20]: no seed in $SEEDS produced a FENCE-ATTRIBUTED divergence under the LOCAL " +
                "delivered frontier. The wrong seam is no longer observably harmful by this rig, " +
                "so `[KE3-20]`'s witness is gone again — widen the adversary, never weaken the " +
                "check, and re-derive the widening's provenance in [BS13_PIN_RETIRED]. " +
                "diverging=$diverging failures=${sweep.failures.map { it.seed to it.message }}",
        )
    }

    /**
     * Feature rule 5, in the only form this rig supports — and the substitution is MEASURED, not
     * a convenience. See [BS13_PIN_RETIRED].
     *
     * `DstRun.assertDeterministic()` (trace-digest reproduction) is what the bead prescribes. It
     * **does not pass on this graph under a `ChurnGenerator`-drawn plan that contains a
     * `DepartureMode.PARTITION_SUSPEND` departure later rejoined or healed** — computenet-l0gd
     * later localized it to exactly that condition (see this class's KDoc and `doc/dst-rig.md`
     * §"A peering that re-opens mid-run is outside the determinism contract"); the seed-dependence
     * recorded below is that condition showing through before it was named. The cause is not
     * this task's: a 2x2 corner measurement on seeds 1/8/9/19 at budget 40_000 found the digest
     * differing between two back-to-back runs with **both** step hooks removed and the full fault
     * plan in place, and again with both hooks installed and **no** folded faults at all (bare
     * `churnPlan`). Neither hook and neither fault is the cause.
     *
     * **Scope of that claim, MEASURED in the 9sm.4.4 review** and narrower than "the churn mesh
     * is not reproducible": the sibling BS-5 graph — none of this task's hooks — is likewise not
     * deterministic on seed 62, which is what puts the cause upstream of this task. But
     * `ChurnMeshTest."two runs of one churn plan produce the same trace digest"` PASSES today on a
     * hand-built `ChurnPlan`, and the bare-`churnPlan` corner is **seed-dependent** (of seeds
     * 1/8/9/19/62/87/107 at `runs = 3`, seeds 62 and 87 reproduced and the rest did not). So what
     * is unreproducible is a generated churn plan on this mesh, not `ChurnMesh` as such. Filed as
     * its own item (computenet-l0gd) rather than papered over here.
     *
     * What IS reproducible is the **verdict**, which is the property rule 5 exists to protect: the
     * recorded seed must still be the seed that resurrects, run after run, so the pin is a pin and
     * not a lucky draw. [BS12_SEED] is re-run [PIN_RUNS] times and every run must hold its verdict.
     *
     * **Only the STABLE half of that pair survives.** computenet-nwnl retired the LOCAL half onto
     * the sweep-level discriminator after measuring that NO seed reaches [PIN_RUNS] of [PIN_RUNS]
     * any more; see [BS13_PIN_RETIRED] for the numbers and the reasoning.
     */
    @Test
    fun `the recorded seed reproduces its verdict_BS12`() {
        fun run(trigger: GcSafetySweep.Trigger, seed: Long): List<String> =
            (1..PIN_RUNS).map {
                MeshConvergences.observing {
                    DstRun(
                        GcSafetySweep.graph(trigger),
                        GcSafetySweep.plan(seed),
                        BUDGET,
                        checks.getValue(trigger),
                    ).execute()
                }
            }.map { it.failingCheck?.message ?: it.outcome.name }

        // The LOCAL pin is RETIRED, not deleted — it moved to the sweep level, in the BS-13
        // arm's `fenceAttributed.isNotEmpty()` assertion. [BS13_PIN_RETIRED] carries the
        // measurement that forced the move and the provenance of what replaced it. The STABLE
        // pin below is untouched.

        // The STABLE pin — FLIPPED by computenet-pay7, not deleted, and deliberately kept on
        // the SAME seed. [BS12_SEED] was chosen by computenet-v2ka precisely because it
        // resurrected on 5 of 5 dedicated re-runs; with the re-admission fence in place it must
        // now resurrect on NONE of them. Holding the old witness and inverting its verdict is
        // stronger evidence than picking a fresh seed would be, because the seed's provenance is
        // that it used to fail.
        val stableMessages = run(GcSafetySweep.Trigger.STABLE, BS12_SEED)
        println("[PIN] STABLE seed=$BS12_SEED -> $stableMessages")
        assertTrue(
            stableMessages.none { it == GcSafetySweep.RESURRECTION_FAILURE },
            "[KE3-23] branch G: the recorded STABLE seed $BS12_SEED resurrected on 5 of 5 runs " +
                "before the re-admission fence and must resurrect on NONE of them now. It is " +
                "never replaced by a friendlier seed. outcomes=$stableMessages",
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

    /**
     * The adversary actually fired, in the forms this rig can honestly promise.
     *
     * **Churn events are asserted per seed**: they are played from the plan, so a missing one is a
     * real defect. Of the five folded CHA1 faults, **the three parks and `gc-reorder` are asserted
     * PER SEED** ([PER_SEED_FAULT_IDS]) — MEASURED to fire on 200 of 200 seeds on BOTH the STABLE
     * and LOCAL arms in the feature reviewer's independent run (`computenet-9sm.4.4`'s task review,
     * corroborated again by this bead's own re-run), so a per-seed bar costs nothing today and is a
     * real property, not a lucky range. **`gc-dup` alone stays asserted sweep-wide**
     * ([SWEEP_WIDE_FAULT_ID]), with the seeds it was inert on named. That is a deliberate divergence
     * from the bead's "assert per seed that the fired fault-id set contains … `gc-dup`", and it is
     * MEASURED: `gc-dup` is a probability-0.5 duplicator on `peer1<->peer2`, so a seed whose churn
     * leaves that edge nearly idle can draw no duplicate at all (seed 91 of 200, reproduced in every
     * run so far, implementer and reviewer alike). Per-seed the assertion would be a coin-flip
     * dressed as a property; sweep-wide it still catches an adversary that never fires, which is
     * what the clause is for.
     */
    private fun assertAdversaryFired(tag: String, sweep: civictech.testkit.dst.DstSweepReport) {
        val drawnModes = mutableSetOf<DepartureMode>()
        val inert = GcSafetySweep.faultIds.associateWith { mutableListOf<Long>() }
        sweep.entries.forEach { entry ->
            val plan = StableFrontierChurnSweep.churnPlan(entry.seed)
            val fired = entry.report?.appliedFaults.orEmpty().filter { it.fired > 0 }.map { it.id }.toSet()
            val plannedEvents = plan.events.map { it.id }.toSet()
            assertTrue(
                plannedEvents.all { it in fired },
                "$tag seed ${entry.seed}: every planned churn event must fire, or the adversary proves " +
                    "nothing; missing=${plannedEvents - fired} fired=$fired",
            )
            GcSafetySweep.faultIds.forEach { id -> if (id !in fired) inert.getValue(id) += entry.seed }
            assertTrue(
                PER_SEED_FAULT_IDS.all { it in fired },
                "$tag seed ${entry.seed}: ${PER_SEED_FAULT_IDS - fired} must fire on every seed — " +
                    "MEASURED 0 of 200 inert on either arm in every run so far, so a miss here is a " +
                    "real regression, not a coin-flip; fired=$fired",
            )
            plan.events.filterIsInstance<DepartEvent>().forEach { drawnModes += it.mode }
        }
        println("[$tag] folded faults inert on: ${inert.filterValues { it.isNotEmpty() }}")
        val duplicatorInertSeeds = inert.getValue(SWEEP_WIDE_FAULT_ID)
        assertTrue(
            duplicatorInertSeeds.size < sweep.total,
            "$tag: folded fault $SWEEP_WIDE_FAULT_ID never fired on ANY seed, so the adversary it " +
                "claims is absent",
        )
        assertTrue(
            drawnModes.containsAll(DepartureMode.entries),
            "$tag: the sweep must draw every departure mode across its range: drawn=$drawnModes",
        )
    }

    companion object {
        /**
         * Recorded, and **never narrowed after a failure**. MEASURED wall time for the pair at this
         * range: ~5.0 s (BS-12) + ~4.4 s (BS-13) on a 16-core macOS host, well inside the ~60 s
         * budget the bead sets, so the range is the bead's full ESTIMATE rather than a subset.
         */
        private val SEEDS = 1L..200L

        private const val BUDGET: Int = 40_000

        /** Re-runs behind each recorded seed's pin. MEASURED: 8 of 8 for six candidate seeds. */
        private const val PIN_RUNS: Int = 5

        /**
         * **The retired LOCAL (`[KE3-20]`, BS-13) per-seed pin — computenet-nwnl.** This is a
         * documentation constant: it holds the provenance of a pin that no longer exists, so the
         * next agent to look for `BS13_SEED` finds the measurement rather than an absence.
         *
         * ## What the pin was, and the three times it was re-derived
         *
         * A recorded seed that must be observably harmed on [PIN_RUNS] of [PIN_RUNS] dedicated
         * re-runs, so the wrong seam's witness is a pin and not a lucky draw. It was chosen by
         * measurement every time and **never replaced by a friendlier seed**: 62 (three 200-seed
         * sweeps intersecting on `{62, 87, 107, 138, 170, 175}`, each then 8 of 8), then 126 when
         * computenet-v2ka's del-dot began consuming tag counters, then 18 when computenet-pay7's
         * re-admission fence turned the LOCAL arm's resurrections into divergences.
         *
         * ## Why it is retired rather than re-derived a fourth time
         *
         * computenet-vhlm keyed the fence on `(element, tag)`, closing a soundness hole in which
         * a rejoining incarnation's re-minted tag counter fenced a DIFFERENT, LIVE element.
         * **Seed 18's LOCAL harm WAS that collision** — vhlm's reviewer restored the old keying
         * by mutation and seed 18 reproduced 5 of 5 with the fence-attribution message. Removing
         * the collision removed the witness, and it removed it from every other candidate too:
         * the LOCAL arm's harmed set is now 0-5 of 200 and its membership moves between runs.
         *
         * MEASURED on darwin/arm64, 16-core, load1 6-13, seeds 1..200, budget 40_000, 2026-09-06,
         * dedicated [PIN_RUNS]-run pins on EVERY candidate observed across the widened sweeps:
         *
         *   seed    4 -> 4/5      seed  132 -> 4/5      seed  145 -> 1/5
         *   seed  181 -> 1/5      seed   12 -> 0/5      seed   89 -> 0/5
         *   seed  154 -> 0/5      seed  149 -> 0/5      seed  165 -> 0/5
         *
         * and, from computenet-vhlm on the same host: 70 -> 2/5, 146 -> 3/5, 181 -> 0/5,
         * 90 -> 0/5. **Nothing reaches 5 of 5.** The bead forbids recording a seed below that
         * bar, and it is the right prohibition: a 4-of-5 seed is a 20%-flaky required check.
         *
         * ## What replaced it, and why that is not a weakening
         *
         * The BS-13 arm's `fenceAttributed.isNotEmpty()` assertion — the sweep-level
         * discriminator. It is a statement about the same property over the same [SEEDS] range,
         * and it is sharper than the pin in one respect and blunter in another, deliberately:
         *
         *  - SHARPER: the pin accepted ANY harm on one seed, including a rig-floor divergence
         *    the CONTROL arm also produces. The discriminator accepts only
         *    [GcSafetySweep.FENCE_ATTRIBUTED_DIVERGENCE_FAILURE] — a divergence established by a
         *    direct read of `ReclaimedDots` — and the BS-12 arm asserts that same class EMPTY on
         *    the STABLE trigger. One adversary, opposite verdicts.
         *  - BLUNTER: it does not name a seed, because this rig cannot honestly name one
         *    (`ChurnMesh`'s determinism caveat, computenet-l0gd). Pinning a set by number was
         *    already unsound here; pinning one seed by repeated re-run is what has now run out.
         *
         * MEASURED across ten 200-seed sweeps on the widened adversary, same host and date:
         * fence-attributed LOCAL seeds = 5, 3, 3, 5, 4, 3, 3, 5, 3, 4 — **non-empty on 10 of 10,
         * minimum 3**, and in all ten every LOCAL divergence was fence-attributed. Against the
         * UNWIDENED adversary the same measurement over seven sweeps was 2, 2, 1, 1, **0**, 2, 2
         * — which is exactly the intermittently-red assertion computenet-nwnl was filed for, and
         * is why the widening is part of this fix rather than optional polish.
         */
        private const val BS13_PIN_RETIRED: String =
            "retired by computenet-nwnl; replaced by the BS-13 arm's sweep-level " +
                "fence-attribution assertion — see this constant's KDoc for the provenance"

        /**
         * The recorded STABLE (`[KE3-23]`, BS-12) seed. It was chosen as the branch-F-B witness
         * — the seed that resurrected on 5 of 5 dedicated re-runs — and computenet-pay7
         * deliberately KEEPS it while inverting its verdict: with the re-admission fence in place
         * the same seed must resurrect on none of them. Its provenance is the evidence.
         */
        private const val BS12_SEED: Long = 126L

        /**
         * The two folded CHA1 faults asserted PER SEED in [assertAdversaryFired] — MEASURED to fire
         * on 200 of 200 seeds on both arms, unlike [SWEEP_WIDE_FAULT_ID].
         */
        private val PER_SEED_FAULT_IDS: Set<String> =
            setOf("gc-park", "gc-park-b", "gc-park-c", "gc-reorder")

        /**
         * The one folded CHA1 fault that stays asserted sweep-wide: a probability-0.5 duplicator
         * that legitimately draws nothing on an idle seed (measured: seed 91 of 200, every run).
         */
        private const val SWEEP_WIDE_FAULT_ID: String = "gc-dup"

        private val stableRoot = File("build/dst-stability/gc-sweep-stable")
        private val noneRoot = File("build/dst-stability/gc-sweep-none")
        private val localRoot = File("build/dst-stability/gc-sweep-local")

        /** Set by the BS-12 arm, read by BS-13's comparison line. */
        private var stableResurrecting: Set<Long> = emptySet()
        private var stableDisagreeing: Set<Long> = emptySet()
        private var stableDiverging: Set<Long> = emptySet()

        /** Seeds where the fence itself caused the divergence — computenet-vhlm. Asserted EMPTY. */
        private var stableFenceAttributed: Set<Long> = emptySet()

        /** Set by the no-reclaimer control arm; the floor the other arms are read against. */
        private var controlResurrecting: Set<Long> = emptySet()
        private var controlDiverging: Set<Long> = emptySet()

        /**
         * Every failure message this sweep accounts for. A message outside it is something
         * neither arm has classified, and both arms fail on it rather than absorbing it.
         */
        /**
         * The two classes that mean the mesh was actually damaged, as opposed to the reference
         * fold merely being unable to express compaction ([GcSafetySweep.DISAGREEMENT_FAILURE],
         * the tolerated F-A class).
         */
        /**
         * The bound on STABLE membership divergence.
         *
         * ## computenet-vhlm, 2026-09-06 — the excess is GONE, and the paragraph that argued it
         * away was arguing away a BUG
         *
         * The wording immediately below is computenet-pay7's, preserved VERBATIM because it is
         * what this bound was raised 10 -> 12 to accommodate, and because its central inference
         * turned out to be true and irrelevant at the same time:
         *
         * > MEASURED with the re-admission fence (computenet-pay7) at **5-8 of 200 across four
         * > 200-seed runs**, against a no-reclaimer CONTROL that diverged on 1-4 in the same
         * > runs. That excess over the control is recorded, not explained away — and it is not
         * > the fence's own hazard, for a reason the workload makes checkable: `removeSchedule`
         * > removes only ODD-ordinal writes, so an EVEN-ordinal element's add-tag never enters
         * > any `dels` entry, can never be recorded by `compactBelow`, and therefore cannot be
         * > in the fence at all. Four of the seven seeds diverging on the last run differ by
         * > exactly one such element (`peer1-22`, `peer2-20`, `peer1-16` — all even), and the
         * > remaining three differ by `peer2-23`, the same last-write straggler shape the
         * > CONTROL arm itself produces. What compaction changes is the traffic: the fence
         * > removes the discard/re-admit/re-emit churn (`discarded` falls from ~64_000 to
         * > ~5_900 over the sweep), so the rig's own late-write floor is reached on more seeds.
         * > That is the honest reading, and it is a bounded-schedule observation rather than a
         * > proof.
         *
         * computenet-vhlm replaced that inference with the MEASUREMENT its acceptance asked for
         * — [GcSafetySweep.FENCE_ATTRIBUTED_DIVERGENCE_FAILURE], a direct read of
         * `ReclaimedDots` — and the measurement said the opposite. The four EVEN-ordinal seeds
         * the inference exonerated ([18, 114, 159, 169]) were fenced anyway, at every replica
         * lacking the element and on ALL of its live tags. The inference was right that the
         * element's own tag could never be recorded; what it missed is that `ReclaimedDots` was
         * keyed on `(sourceId, counter)` and `SetCell`'s tag source is REUSED across a replica's
         * incarnations while its counter restarts at 0 — so a dot reclaimed from a departed
         * incarnation fenced a different, live element minted by its rejoin. See
         * `ReclaimedDots`' KDoc §"Why the key is (element, tag) and not the tag alone".
         *
         * Keying the fence on `(element, tag)` closes it. MEASURED across four 200-seed runs,
         * darwin/arm64 16-core, load1 3.7-11.2, budget 40_000, 2026-09-06:
         *
         *   STABLE resurrecting     []  []  []  []          (branch G, unchanged)
         *   STABLE fence-attributed []  []  []  []          <- the new assertion, empty every run
         *   STABLE diverging         2   3   1   4
         *   CONTROL diverging        3   3   3   1
         *
         * and across four more on the same host at load1 4.6-10.4 (task review, same date):
         *
         *   STABLE resurrecting     []  []  []  []
         *   STABLE fence-attributed []  []  []  []
         *   STABLE diverging         1   5   3   4
         *   CONTROL diverging        3   3   3   4
         *
         * Every remaining STABLE divergence differs by `peer2-23` with attribution NONE, and the
         * CONTROL arm — which now prints its own per-seed DIVERGE lines — produces exactly that
         * shape and nothing else. The "same shape as the control" claim is an artifact now, not
         * an assertion.
         *
         * WHAT THAT DOES AND DOES NOT SETTLE, because the distinction is easy to overstate and
         * this KDoc did overstate it (task review, computenet-vhlm). computenet-pay7's criterion
         * 2 asked for divergence "no worse than the Trigger.NONE control arm's, measured in the
         * same run". The two arms are now in ONE BAND — 1-5 STABLE against 1-4 CONTROL, where
         * before the re-key they were 5-8 against 1-4 — and the ~3x excess computenet-vhlm was
         * filed over is gone. The per-run INEQUALITY, however, does not hold on every run: run 4
         * of the first table is 4 against 1, and run 2 of the second is 5 against 3. On a rig
         * that is not reproducible and whose two arms each move by several seeds between runs, a
         * per-run count comparison is not something a harness can assert, and this one never did.
         * What carries criterion 2 is therefore the ATTRIBUTION assertion — empty on 8 of 8 runs
         * above, a statement about MECHANISM — with this absolute count bound behind it. Read
         * "criterion 2 is met" as that, not as an inequality that holds run by run.
         *
         * The bound is deliberately LEFT at 12 rather than lowered to the new band: the rig is
         * not reproducible, and a bound tightened onto a four-run maximum would fail for reasons
         * that have nothing to do with the property. It is a ceiling on a known failure mode, not
         * a pin on the current number. It has never been raised by computenet-vhlm.
         *
         * The bound is set above the observed maximum because the rig is not reproducible (see
         * the pin test's KDoc) and both arms move by several seeds between runs; the failure it
         * exists to catch is still an order of magnitude away (a per-source re-admission floor
         * measured 31-33, and a fence WITHOUT the repair emission measured 30 — see
         * `SetCell.compactBelow`'s and `SetCell.applyRemote`'s KDoc).
         *
         * It is deliberately NOT read off the control arm at runtime: JUnit does not order the
         * two @Test methods, so a cross-arm read would make this assertion depend on which ran
         * first.
         */
        private const val MAX_STABLE_DIVERGING: Int = 12

        private val HARMED: Set<String> = setOf(
            GcSafetySweep.RESURRECTION_FAILURE,
            GcSafetySweep.MEMBERSHIP_DIVERGENCE_FAILURE,
            // computenet-vhlm: the attributed sub-class of a divergence is still a divergence, so
            // a pin that accepted the parent class must accept it, or the pin would go green on a
            // STRICTLY WORSE outcome than the one it was recorded for.
            GcSafetySweep.FENCE_ATTRIBUTED_DIVERGENCE_FAILURE,
        )

        private val CLASSIFIED: Set<String> = setOf(
            GcSafetySweep.RESURRECTION_FAILURE,
            GcSafetySweep.MEMBERSHIP_DIVERGENCE_FAILURE,
            GcSafetySweep.FENCE_ATTRIBUTED_DIVERGENCE_FAILURE,
            GcSafetySweep.DISAGREEMENT_FAILURE,
        )

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
            noneRoot.deleteRecursively()
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




