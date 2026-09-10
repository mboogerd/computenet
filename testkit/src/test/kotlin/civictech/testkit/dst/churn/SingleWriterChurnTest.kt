package civictech.testkit.dst.churn

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.replication.LeaderMark
import civictech.cell.replication.SingleWriterReplicable
import civictech.cell.replication.SingleWriterReplication
import civictech.cell.replication.Stamped
import civictech.cell.replication.forwardWrites
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * BS-14 — leader churn on a single-writer replica set ([CHA3-50], [CHA3-51], [CHA3-52];
 * feature computenet-umx.2 §4.6, §9 risk 5).
 *
 * ## The branch this file took, and why it changed under computenet-f7h.1
 *
 * Feature §9 risk 5 flags the honest-outcome fork: `SingleWriterReplication` ships
 * EXPLICIT/orchestrated designation only (its own KDoc, `SingleWriterReplication.kt`: "this
 * ticket ships EXPLICIT/orchestrated designation only — `designateLeader` is the manual-failover
 * hook the spec declares the default"), so if explicit-only failover admits no interleaving that
 * produces a split-brain window, the measurement is vacuous and the finding is a measured zero.
 *
 * **Before computenet-f7h.1, it was not vacuous: there was a real, non-zero in-process window,
 * measured here.** The mechanism was `designateLeader`'s scope: a [LeaderMark] folded into
 * **one peer's own** `leaderMarks` map by a direct call and was *not* gossiped — no announcement
 * path between peers for it (contrast `Replication`'s membership, which rides
 * `LocationRegistry.onPublish`). Failing over a two-peer set took **two** calls, one per peer,
 * and the state between them was a state of the system, not a race: whichever call went first
 * decided what that state was — promote-first left both peers believing they led; demote-first
 * left neither.
 *
 * **computenet-f7h.1 removed that per-engine fold.** The [LeaderMark] fold now lives on
 * [civictech.cell.host.InstanceIndex] — one fold per [LocationRegistry], not one per
 * [SingleWriterReplication] engine — and role application hangs off the registry's
 * `onLeaderMark` hook, so every engine sharing a registry applies its own role from the SAME
 * adopted fold, in the SAME step, regardless of which engine's `designateLeader` call triggered
 * it (`SingleWriterReplication.kt`'s `init`, f7h.1-D4). This file's two peers share one
 * [LocationRegistry] (see [SwSet]'s KDoc), so **in process the window is now zero by
 * construction, on both orders** — there is no longer an interleaving between "the incoming
 * leader is up" and "the outgoing leader is down" for a driver to land a write in, and the order
 * of the two `designateLeader` calls no longer decides anything observable. That is measured
 * directly below rather than argued: both arms assert `splitBrainWindow == 0`, and one assertion
 * pins that the two orders now produce the IDENTICAL belief sequence.
 *
 * **The window did not vanish from the spec — it moved.** [LeaderMark] still folds on ONE
 * registry at a time; a real deployment folds it on every peer's OWN registry, over the wire, and
 * nothing about computenet-f7h.1 makes a fold ATOMIC ACROSS registries. That cross-registry
 * window belongs to feature computenet-f7h.2 (`MEM1` epic) — a `Peering` bridge between two
 * `:kernel` hosts, which `:testkit`'s main source set cannot build: there is no KSP configuration
 * here to generate the `@Contract` codecs a wire bridge needs (the same reason [SwSet] gives for
 * sharing one registry rather than bridging two). That is a stated LIMITATION on what this file
 * can measure, not a vacuous zero: the zero below is exactly correct for the shared-registry,
 * in-process case this file drives, and computenet-f7h.2 is where the cross-registry case has to
 * be measured instead. No `MEM1` requirement id is claimed as covered here.
 *
 * Reporting this does not choose between 95 §R1's directions and implements no election
 * ([CHA3-52], [CHA3-84]) — see [NO_ELECTION_DEFINED], asserted at the bottom of this file.
 *
 * ## What the write accounting measures, and what it depends on
 *
 * [CHA3-51] asks whether an accepted write is lost or duplicated across the transition. Epoch
 * *fencing* — "deltas stamped below the current epoch are inert" (spec 42) — is implemented by
 * the **cell**, not by [SingleWriterReplication]: it is the `if (value.epoch < currentEpoch)
 * return` in the replicable's own delta inlet. `:testkit`'s main source set ships no
 * [SingleWriterReplicable], so [SwCounterCell] below mirrors `:kernel`'s own
 * `SingleWriterReplicationTest.SwCounterCell`, deliberately, so that the accounting is measured
 * against the reference implementation the kernel tests its fencing rule with rather than against
 * a fixture written to produce a result. The mirror is exact in every part the accounting reads —
 * the fencing inlet, the `onLinked` catch-up, `becomeLeader`/`becomeFollower`, the write API's
 * leader check — and drops two things this measurement never exercises: the kernel copy also
 * implements `Stateful.snapshot()` and a `mark(Leased<String>)` write method.
 *
 * The measured answer for the promote-first transition is **4 accepted, 4 at the successor: no
 * accepted write lost, none duplicated** — unchanged by computenet-f7h.1, and re-measured against
 * the new one-fold engine rather than copied forward: the successor's catch-up still raises the
 * outgoing leader's epoch (asserted mid-test) before its post-transition write is stamped, so the
 * write is still not fenced; only WHEN peerA steps down moved (before that write now, instead of
 * after), which does not change what gets counted. Its limits are stated on
 * `MEASURED_OBSERVED_TOTAL`. A zero here is a result about **this** interleaving; 95 §R1's "in
 * every interleaving" is research-gated and is not answered.
 *
 * **computenet-yqgd decided that "duplicated across the transition" must be read at every
 * instance, not the successor alone**, because the successor-only zero above is true and
 * incomplete: the DEMOTED instance measurably duplicates exactly the 2 pre-transition writes on
 * top of state it already held, in both orders (`MEASURED_DEMOTED_TOTAL_PROMOTE_FIRST`,
 * `MEASURED_DEMOTED_TOTAL_DEMOTE_FIRST`). (The bead that filed this, computenet-yqgd, describes
 * that duplication as the demoted total ending "exactly twice the successor's" in both orders —
 * true of the demote-first arm (4 == 2*2) but not this one (6 != 2*4); the invariant that DOES
 * hold in both is duplicated-at-demoted == 2, and this file's arms pin that, with the discrepancy
 * reported on the bead rather than carried forward silently.)
 * `LeaderChurnReport.instanceReadings` carries that reading and both arms below pin it.
 *
 * That dependency is stated rather than hidden, because it bounds the claim: the numbers below
 * are what happens *with that fencing implementation*, and a cell that stamped its outbound
 * deltas from a separate applied-epoch field would account differently. The measurement is
 * reported, not generalised into a property of the kernel.
 *
 * ## Why this drives the mesh directly instead of through [ChurnMesh]
 *
 * [ChurnMesh]'s peers replicate a `civictech.cell.data.Replicable` through `Replication` — the
 * symmetric mergeable mesh. A single-writer set is the *asymmetric* engine, a different kernel
 * type with a different membership story, and adding a single-writer [MeshPayload] means editing
 * `PeerHandles.kt`, which is a sibling task's file claim under epic computenet-umx.
 *
 * ## The interleaving is CONSTRUCTED, not generated — stated because it reads otherwise
 *
 * [ChurnSeeds] appears below, but only its **seed** reaches the fixture: `plans(101L..101L)` is
 * evaluated and `plan.seed` names the logical id, while the plan's own events are never executed
 * and never order the designations. The two designation calls, their order, and where each write
 * is issued are written out in the test body. That is deliberate — the subject is one specific
 * two-call orchestration and its mirror image, which is a thing to construct rather than to
 * sample — but it means the numbers below are **not** a generated adversary's, and reading them
 * as a sweep result would overstate them. A sweep over seeds would be a different measurement:
 * it needs a single-writer [MeshPayload], which is a sibling task's file.
 */
class SingleWriterChurnTest {

    // ------------------------------------------------------------------------------- fixture

    /** The write API. Non-idempotent by construction — exactly why this needs a leader. */
    interface SwCounterOps {
        fun increment(amount: Long)
    }

    /**
     * A mirror of `:kernel`'s `SingleWriterReplicationTest.SwCounterCell` — the reference
     * implementation of spec 42's fencing rule. See the class KDoc for why it is copied rather
     * than adapted, and what that means for the numbers.
     */
    class SwCounterCell(override val ref: CellRef) : SingleWriterReplicable<Long>, Cell {

        val writeInlet = registerPort("writeInlet", FanInlet.create<SwCounterOps>())
        override val deltaOutlet = registerPort("deltaOutlet", FanOutlet.create<Propagate<Stamped<Long>>>())
        private val deltaInletPort = registerPort("deltaInlet", FanInlet.create<Propagate<Stamped<Long>>>())
        override val deltaInlet: Use<Propagate<Stamped<Long>>> get() = deltaInletPort

        var total: Long = 0
            private set
        var leading: Boolean = false
            private set
        var currentEpoch: Long = -1
            private set

        private val realApi = object : SwCounterOps {
            override fun increment(amount: Long) {
                check(leading) { "not the leader" }
                total += amount
                if (amount != 0L) deltaOutlet.call.propagate(Stamped(currentEpoch, amount))
            }
        }

        init {
            deltaInletPort.serve(object : Propagate<Stamped<Long>> {
                override fun propagate(value: Stamped<Long>) {
                    // fencing (spec 42): a delta stamped below the current epoch is inert
                    if (value.epoch < currentEpoch) return
                    currentEpoch = maxOf(currentEpoch, value.epoch)
                    total += value.delta
                }
            })
            deltaOutlet.linking.onLinked = { link ->
                if (leading && total != 0L) deltaOutlet.at(link.to).propagate(Stamped(currentEpoch, total))
            }
        }

        override fun becomeLeader(epoch: Long) {
            leading = true
            currentEpoch = epoch
            writeInlet.serve(realApi)
        }

        override fun becomeFollower(leaderRef: CellRef, epoch: Long, registry: LocationRegistry) {
            leading = false
            currentEpoch = epoch
            writeInlet.delegate(forwardWrites(writeInlet.clazz, "writeInlet", leaderRef, registry))
        }

        override fun currentState(): Long = total
        override fun adoptState(state: Long) {
            total = state
        }
    }

    /**
     * A two-peer single-writer set on one shared [LocationRegistry].
     *
     * Shared rather than bridged: the subject is *leadership* belief and write accounting, and a
     * `Peering` bridge would add a wire-encoding requirement (`@Contract`-generated codecs) that
     * `:testkit` has no KSP configuration to satisfy. Membership visibility is not what is under
     * measurement here — every instance is visible to every other throughout — so the shared
     * directory removes a variable rather than hiding one.
     *
     * **Each peer still keeps its own [SingleWriterReplication] engine, but computenet-f7h.1
     * moved the [LeaderMark] fold off that engine and onto this shared [LocationRegistry]**
     * ([civictech.cell.host.InstanceIndex]). One fold per registry, not one per engine, is
     * exactly why the in-process window measured below is zero: every engine sharing this
     * registry applies its role from the SAME adopted mark, in the SAME step. Bridging two
     * SEPARATE registries — one per peer, over the wire — would restore two independent folds and
     * a real window again; that is feature computenet-f7h.2's case, which `:testkit` cannot build
     * here (no KSP configuration for a `Peering` bridge, same limitation as above — see the class
     * KDoc).
     */
    private class SwSet(seed: Long) {
        val controller = SimulationController()
        val registry = LocationRegistry()
        val logicalId: UUID = UUID.nameUUIDFromBytes("single-writer-churn:$seed".toByteArray())

        val aHost = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val bHost = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val aReplication = SingleWriterReplication(registry)
        val bReplication = SingleWriterReplication(registry)

        val aRef = CellRef(logicalId, 0)
        val bRef = CellRef(logicalId, 1)
        val epoch0 = LeaderMark(logicalId, epoch = 0, leaderRef = aRef)
        val epoch1 = LeaderMark(logicalId, epoch = 1, leaderRef = bRef)

        val a = SwCounterCell(aRef)
        val b = SwCounterCell(bRef)

        init {
            aReplication.replicate(a, aHost, epoch0)
            bReplication.replicate(b, bHost, epoch0)
            drain()
        }

        fun drain(): Int = controller.runToIdle(budget = 10_000)

        /** Every instance whose own state says it is leading — the belief read [CHA3-50] asks for. */
        fun believedLeaders(): List<String> =
            listOfNotNull("peerA".takeIf { a.leading }, "peerB".takeIf { b.leading })

        /**
         * Issue one write at [at] and report which instance actually applied it, or null if
         * nobody did.
         *
         * Determined by which cell's total moved, not by which cell was believed to be leading:
         * "accepted" has to mean *applied*, or the lost-write accounting would be counting
         * intentions. When two cells move (an applied write plus a relayed delta the other side
         * also absorbed) the leading one is reported as the acceptor and the extra unit shows up
         * in [LeaderChurnReport.duplicatedWrites], which is where a duplication belongs.
         */
        fun issue(at: SwCounterCell): String? {
            val beforeA = a.total
            val beforeB = b.total
            at.writeInlet.call.increment(1)
            drain()
            val moved = buildList {
                if (a.total != beforeA) add("peerA" to a.leading)
                if (b.total != beforeB) add("peerB" to b.leading)
            }
            return moved.firstOrNull { it.second }?.first ?: moved.firstOrNull()?.first
        }
    }

    /**
     * Re-run the baseline and the two `designateLeader` calls, in the given order, on a FRESH
     * [SwSet] with none of an arm's own extra write ticks, and return the belief sample at each
     * of the four resulting ticks (baseline, 2 writes, first call, second call) — the shape
     * [MEM1-20]'s local half compares: one fold per registry, so which engine's call goes first
     * no longer changes what either arm believes at any of these four points. Used by the
     * demote-first arm to pin that its own sequence is identical to the promote-first order's.
     */
    private fun beliefsAfterDesignation(
        firstCall: (SwSet) -> Unit,
        secondCall: (SwSet) -> Unit,
    ): List<List<String>> {
        val set = SwSet(ChurnSeeds.plans(101L..101L).single().seed)
        val measurement = LeaderChurnMeasurement(set::believedLeaders)
        measurement.tick("baseline: designateLeader(epoch=0, peerA) on both peers")
        repeat(2) { i -> set.issue(set.a)?.let { measurement.acceptedWrite(i, it) } }
        measurement.tick("2 writes applied by the epoch-0 leader")
        firstCall(set)
        set.drain()
        measurement.tick("first designateLeader call")
        secondCall(set)
        set.drain()
        measurement.tick("second designateLeader call")
        return measurement.report(observedTotal = set.b.total).samples.map { it.believedLeaders }
    }

    // ------------------------------------------------------------- BS-14, promote-first arm

    @Test
    fun `BS-14 promote-first designation opens a real split-brain window, and it is measured`() {
        // [CHA3-53]'s stream, consumed rather than duplicated — but only for its seed: the
        // interleaving below is constructed, not drawn from the plan. See the class KDoc.
        val plan = ChurnSeeds.plans(101L..101L).single()
        val set = SwSet(plan.seed)
        val measurement = LeaderChurnMeasurement(set::believedLeaders)

        measurement.tick("baseline: designateLeader(epoch=0, peerA) on both peers")
        repeat(2) { i -> set.issue(set.a)?.let { measurement.acceptedWrite(i, it) } }
        measurement.tick("2 writes applied by the epoch-0 leader")

        // The failover, promote-first: the INCOMING leader's engine folds the new mark. Under
        // computenet-f7h.1's one-fold-per-registry, that single call is the WHOLE transition:
        // the registry's onLeaderMark hook (SingleWriterReplication.kt's `init`) notifies every
        // engine sharing this registry, including peerA's — so peerA steps down in the SAME step
        // peerB is promoted, not two calls apart. See the class KDoc for the pre-f7h.1 mechanism
        // this used to measure.
        set.bReplication.designateLeader(set.epoch1)
        set.drain()
        measurement.tick("designateLeader(epoch=1, peerB) on peerB")

        // Pinned here rather than narrated: BOTH roles flip in this one step, so there is no gap
        // where the outgoing leader is "still serving" — peerA's own onLeaderMark subscriber runs
        // `becomeFollower` from the identical fold that promotes peerB. `currentEpoch` still rises
        // to 1 (spec 42's fencing rule), because that follows from the SAME fold's role
        // application, not from a separate catch-up step.
        assertEquals(1L, set.a.currentEpoch, "the fold raises every subscriber's applied epoch together")
        assertFalse(set.a.leading, "the outgoing leader already stepped down in the same fold (computenet-f7h.1)")
        assertTrue(set.b.leading, "and the incoming leader is already promoted in the same fold")

        // A write issued right after the transition. There is no window left for it to land in:
        // it is issued at peerA, which is already a follower, so it is forwarded by peerA's
        // delegate (`SingleWriterReplication.kt`'s `forwardWrites`) to peerB and applied there.
        set.issue(set.a)?.let { measurement.acceptedWrite(2, it) }
        measurement.tick("write issued inside the window")

        // The second call folds nothing new: [MEM1-09] reads it as a duplicate of the mark
        // already adopted by the first call, so no roles are re-applied.
        assertFalse(
            set.aReplication.designateLeader(set.epoch1),
            "a duplicate of the already-folded mark is rejected ([MEM1-09])",
        )
        set.drain()
        measurement.tick("designateLeader(epoch=1, peerB) on peerA")

        set.issue(set.a)?.let { measurement.acceptedWrite(3, it) }
        measurement.tick("post-transition write")

        val report = measurement.report(
            observedTotal = set.b.total,
            instanceReadings = listOf(
                InstanceStateReading("peerA", set.a.total),
                InstanceStateReading("peerB", set.b.total),
            ),
        )

        // [CHA3-50] — computenet-f7h.1 closed the window this arm used to measure: one fold per
        // registry means both peers change belief in the same step, on either order. See the
        // class KDoc for where the window moved (computenet-f7h.2, the wire) and why `:testkit`
        // cannot drive that half.
        assertEquals(0, report.splitBrainWindow, report.summary())
        assertEquals(emptyList(), report.splitBrainSamples, report.summary())

        // [CHA3-51] — the interleaving that produced it, reported alongside the accounting.
        assertEquals(
            listOf(
                "baseline: designateLeader(epoch=0, peerA) on both peers",
                "2 writes applied by the epoch-0 leader",
                "designateLeader(epoch=1, peerB) on peerB",
                "write issued inside the window",
                "designateLeader(epoch=1, peerB) on peerA",
                "post-transition write",
            ),
            report.interleaving,
        )
        assertEquals(0, report.acceptedDuringSplitBrain.size, report.summary())

        // The accounting itself is asserted exactly, whatever it says — see the class KDoc on what
        // it depends on. It is a measurement of this transition, not a property claim about the
        // kernel. computenet-f7h.1 changed WHEN peerA steps down (before the "inside window"
        // write now, not after) but not the totals below: they measure the same as at c491bd6a1.
        assertEquals(4, report.accepted.size, report.summary())
        assertEquals(4L, report.expectedTotal, report.summary())
        assertEquals(MEASURED_OBSERVED_TOTAL, report.observedTotal, report.summary())
        assertEquals(MEASURED_LOST, report.lostWrites, report.summary())
        assertEquals(MEASURED_DUPLICATED, report.duplicatedWrites, report.summary())

        // computenet-yqgd's decision: [CHA3-51]'s "duplicated across the transition" is read at
        // EVERY instance, not the successor alone. Unchanged by computenet-f7h.1: the demoted
        // leader (peerA) still measurably duplicates exactly the 2 pre-transition writes on top
        // of the state it already held — the successor's from-zero catch-up still ships the
        // leader's CURRENT total (2, at the moment of designation) back onto an already-populated
        // ex-leader rather than a fresh follower (mechanism: `onLinked`'s
        // `Stamped(currentEpoch, total)`); only the SEQUENCING that produces it moved from two
        // designateLeader calls to one folded step (see the class KDoc).
        //
        // NOTE ON THE BEAD'S OWN WORDING: computenet-yqgd's description states the demoted
        // instance ends "exactly twice the successor's total" in both orders. That holds for the
        // demote-first arm (4 == 2*2) but NOT here: 6 != 2*4. The raw numbers the bead reports
        // (6 and 4) are exactly what this arm measures; only the "twice" characterization is
        // wrong for this arm. What actually holds in both arms is duplicated-at-demoted == 2,
        // the pre-transition write count — see the demote-first arm below and the discrepancy
        // reported on the bead.
        val peerAReading = report.instanceReadings.single { it.instance == "peerA" }
        assertEquals(MEASURED_DEMOTED_TOTAL_PROMOTE_FIRST, peerAReading.total, report.summary())
        assertEquals(
            2L,
            peerAReading.duplicated(report.expectedTotal),
            "the demoted instance duplicated exactly the 2 pre-transition writes: ${report.summary()}",
        )
        assertTrue(report.instancesWithDuplicates.map { it.instance } == listOf("peerA"), report.summary())
    }

    // -------------------------------------------------------------- BS-14, demote-first arm

    @Test
    fun `BS-14 demote-first designation closes the window and opens a no-leader gap instead`() {
        val plan = ChurnSeeds.plans(101L..101L).single()
        val set = SwSet(plan.seed)
        val measurement = LeaderChurnMeasurement(set::believedLeaders)

        measurement.tick("baseline: designateLeader(epoch=0, peerA) on both peers")
        repeat(2) { i -> set.issue(set.a)?.let { measurement.acceptedWrite(i, it) } }
        measurement.tick("2 writes applied by the epoch-0 leader")

        // The same failover, the other order: the OUTGOING leader's engine folds the mark first.
        // Under computenet-f7h.1's one-fold-per-registry this no longer matters: the same
        // registry hook notifies both engines from whichever call adopts the mark, so this call
        // already performs the whole transition — promoting peerB in the same step peerA steps
        // down.
        set.aReplication.designateLeader(set.epoch1)
        set.drain()
        measurement.tick("designateLeader(epoch=1, peerB) on peerA")

        // The second call folds nothing new — the same duplicate rejection as the promote-first
        // arm, order no longer matters ([MEM1-09]).
        assertFalse(
            set.bReplication.designateLeader(set.epoch1),
            "a duplicate of the already-folded mark is rejected ([MEM1-09]) — order no longer matters",
        )
        set.drain()
        measurement.tick("designateLeader(epoch=1, peerB) on peerB")

        val report = measurement.report(
            observedTotal = set.b.total,
            instanceReadings = listOf(
                InstanceStateReading("peerA", set.a.total),
                InstanceStateReading("peerB", set.b.total),
            ),
        )

        assertEquals(
            0,
            report.splitBrainWindow,
            "no sample ever saw two leaders under this order: ${report.summary()}",
        )
        assertEquals(emptyList(), report.splitBrainSamples, report.summary())

        // computenet-f7h.1 closed the gap this arm used to open, too: the fold that used to leave
        // this order with a step where NOBODY led (the demoted leader stepped down one call
        // before its successor stepped up) now flips both roles in the same step peerA's call
        // adopts the mark, so there is never a leaderless sample either. See the class KDoc for
        // where the real (cross-registry) gap now lives.
        val gap = report.samples.singleOrNull { it.believedLeaders.isEmpty() }
        assertNull(gap, "no orchestration step is ever leaderless: one fold flips both roles at once (${report.summary()})")

        // [MEM1-20]'s local half, pinned directly rather than argued: one fold per registry means
        // the belief sequence this order produces is IDENTICAL to the promote-first order's, not
        // merely equally window-free. Re-run with the calls reversed on a fresh set and compare.
        assertEquals(
            beliefsAfterDesignation(
                firstCall = { it.bReplication.designateLeader(it.epoch1) },
                secondCall = { it.aReplication.designateLeader(it.epoch1) },
            ),
            report.samples.map { it.believedLeaders },
            "designation order is unobservable in-process now: ${report.summary()}",
        )

        // No write was issued around the transition, and the pre-transition writes are intact at
        // the successor: nothing is lost by this ordering, on this transition.
        assertEquals(2, report.accepted.size, report.summary())
        assertEquals(0L, report.lostWrites, report.summary())
        assertEquals(0L, report.duplicatedWrites, report.summary())

        // computenet-yqgd's decision, measured in this order too, unchanged by computenet-f7h.1:
        // the demoted leader (peerA) again duplicates exactly the 2 pre-transition writes, even
        // though the successor-only accounting above reads clean. Same mechanism as the
        // promote-first arm — demote-first only changes WHICH call the transition rides in on,
        // not what the catch-up does to the demoted instance. Here (and only here) that
        // duplication happens to make peerA's total exactly twice the successor's (4 == 2*2); see
        // the promote-first arm's comment for why that ratio is not the invariant —
        // duplicated == 2 (the pre-transition write count) is.
        val peerAReading = report.instanceReadings.single { it.instance == "peerA" }
        assertEquals(MEASURED_DEMOTED_TOTAL_DEMOTE_FIRST, peerAReading.total, report.summary())
        assertEquals(
            2L,
            peerAReading.duplicated(report.expectedTotal),
            "the demoted instance duplicated exactly the 2 pre-transition writes: ${report.summary()}",
        )
        assertTrue(report.instancesWithDuplicates.map { it.instance } == listOf("peerA"), report.summary())
    }

    // ----------------------------------------------------------------------------- boundary

    @Test
    fun `BS-14 reports measurements only - no election, no R1 direction`() {
        assertTrue(NO_ELECTION_DEFINED.contains("implements NO leader election"), NO_ELECTION_DEFINED)
        assertTrue(NO_ELECTION_DEFINED.contains("chooses NO 95 §R1 direction"), NO_ELECTION_DEFINED)
        assertTrue(NO_ELECTION_DEFINED.contains("G-44"), NO_ELECTION_DEFINED)
        assertTrue(NO_ELECTION_DEFINED.contains("MEM1"), NO_ELECTION_DEFINED)

        // The report type carries no verdict surface at all: everything on it is a count, a
        // sample or the interleaving. A `passed`/`acceptable` field would be this harness
        // choosing a direction, which [CHA3-52] forbids.
        //
        // `declaredFields` alone only sees backing fields, so a getter-only computed property
        // (`val passed get() = ...`, which Kotlin compiles to a `getPassed()` method with no
        // backing field) would be invisible to this pin and could reintroduce exactly the
        // verdict surface [CHA3-52] forbids without ever going red (computenet-fmri). Widen the
        // check to every zero-arg method the class declares as well, stripped of any `get`
        // prefix so a computed property's own name is what gets matched — not kotlin-reflect's
        // `memberProperties` (`:testkit` declares no kotlin-reflect dependency; see
        // testkit/build.gradle.kts), but the same declared-members surface `java.lang.Class`
        // already exposes.
        //
        // Deliberately NOT filtered to `startsWith("get")`: Kotlin's boolean-property convention
        // compiles `val isPassing get() = ...` to `isPassing()`, with no `get` prefix at all, so
        // a `get`-only filter is blind to exactly the shape a boolean verdict would most likely
        // take (measured: such a property left the `get`-filtered pin green). Dropping the filter
        // also covers a verdict exposed as a zero-arg *function* (`fun passed(): Boolean`), which
        // [CHA3-52] forbids just the same. Nothing this type declares today — the twelve property
        // getters plus `summary`, `component1..6`, `hashCode`, `toString` — trips the substring
        // test, so the widening costs no false positive.
        val fields = LeaderChurnReport::class.java.declaredFields.map { it.name }
        val computedGetterNames = LeaderChurnReport::class.java.declaredMethods
            .filter { it.parameterCount == 0 }
            .map { it.name.removePrefix("get").replaceFirstChar(Char::lowercase) }
        val names = fields + computedGetterNames
        assertTrue(
            names.none { it.contains("pass", ignoreCase = true) || it.contains("verdict", ignoreCase = true) },
            "LeaderChurnReport must carry measurements only, found: $names",
        )
    }

    private companion object {
        /**
         * The measured outcome of the promote-first transition, pinned rather than computed, so a
         * change in the kernel's designation path or in the reference fencing implementation goes
         * red here instead of silently re-baselining.
         *
         * The measured result, stated plainly because it is counter-intuitive: **4 writes
         * accepted, 4 present at the successor — no accepted write was lost or duplicated across
         * this transition**. The reason is the epoch bump asserted mid-test: the successor's
         * catch-up raises the outgoing leader's `currentEpoch` in the same fold that demotes it,
         * so its next write ships at the new epoch and is not fenced.
         *
         * **Re-measured under computenet-f7h.1** (one fold per registry, replacing the two-call
         * per-engine fold — class KDoc): this figure is UNCHANGED from before that ticket. What
         * moved is WHEN the outgoing leader steps down — in the same step as the promotion now,
         * rather than one `designateLeader` call later, so it is no longer "accepted while two
         * instances believed they were leading" (the window that produced is now zero) but
         * "accepted immediately after the fold, forwarded by the already-demoted instance". The
         * same delta shipments happen either way, so the count did not change.
         *
         * The limit of that result, in the file rather than only in a report: it is **one
         * transition, on a two-instance set, with writes issued only at the outgoing leader**,
         * against the reference fencing implementation described in the class KDoc, on a
         * **constructed** interleaving rather than a generated one (class KDoc again).
         *
         * And one more limit, which the phrase "none lost, none duplicated" would otherwise
         * overstate on its own: `observedTotal` is the post-transition leader's state, and by
         * itself says nothing about the DEMOTED instance's. Those two states are not guaranteed
         * to agree after a transition: the successor's `onLinked` catch-up ships its total as a
         * *from-zero* delta, and on a failover that delta's target is an already-populated
         * ex-leader rather than a fresh follower. **computenet-yqgd decided this must be
         * accounted, not left as a stated gap**: [MEASURED_DEMOTED_TOTAL_PROMOTE_FIRST] and
         * [MEASURED_DEMOTED_TOTAL_DEMOTE_FIRST] below are that accounting, read via
         * `LeaderChurnReport.instanceReadings` in both arms of this test.
         *
         * None of this is evidence that the split-brain window is harmless in general — the window itself is
         * real and non-zero, and 95 §R1's "prove or refute ... in every interleaving" is a
         * research-gated question this measurement does not answer.
         */
        const val MEASURED_OBSERVED_TOTAL: Long = 4
        const val MEASURED_LOST: Long = 0
        const val MEASURED_DUPLICATED: Long = 0

        /**
         * computenet-yqgd's decision realised: [CHA3-51]'s "duplicated across the transition"
         * read at the DEMOTED instance (peerA), not only the successor. Measured at
         * [MEASURED_OBSERVED_TOTAL] + 2 — the outgoing leader's own 2 pre-transition writes,
         * counted once in its own state and shipped a second time by the successor's from-zero
         * catch-up (`Stamped(currentEpoch, total)`, where `total` was 2 at the moment of
         * designation), landing on top of state the demoted instance already held. The bead that
         * filed this describes the result as the demoted total ending "exactly twice the
         * successor's" in both orders; that arithmetic does not hold here (6 != 2*4) — see the
         * class KDoc for the correction and the invariant that does hold (duplicated == 2).
         *
         * **Re-measured under computenet-f7h.1: unchanged (still 6).** The from-zero catch-up
         * fires from the same fold that demotes peerA rather than from a later, separate call,
         * but it ships the same total (2, peerA's pre-transition state at the moment of
         * designation) onto the same already-populated instance — nothing about the fold being
         * atomic changes what gets shipped or when relative to peerA's own writes.
         */
        const val MEASURED_DEMOTED_TOTAL_PROMOTE_FIRST: Long = 6

        /**
         * The same accounting, demote-first arm: the demoted leader again duplicates exactly its
         * own 2 pre-transition writes, landing at 4 — even though the successor-only reading for
         * this arm is clean (`lostWrites=0`, `duplicatedWrites=0`). Here the result also happens
         * to equal twice the successor's total (4 == 2*2, unlike the promote-first arm); see the
         * class KDoc.
         *
         * **Re-measured under computenet-f7h.1: unchanged (still 4).** computenet-f7h.1 closed
         * the split-brain window this arm's designation order used to open (2, promote-first) as
         * well as the leaderless gap this order used to open (both now 0, class KDoc) — order no
         * longer distinguishes the two arms' belief sequences at all — but the demoted instance's
         * duplication is untouched by either: the same from-zero catch-up ships the same total
         * regardless of which registry hook subscriber runs it or in what order the two
         * `designateLeader` calls arrived.
         */
        const val MEASURED_DEMOTED_TOTAL_DEMOTE_FIRST: Long = 4
    }
}
