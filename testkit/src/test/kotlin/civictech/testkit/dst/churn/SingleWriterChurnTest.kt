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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * BS-14 — leader churn on a single-writer replica set ([CHA3-50], [CHA3-51], [CHA3-52];
 * feature computenet-umx.2 §4.6, §9 risk 5).
 *
 * ## The branch this file took, and the reason
 *
 * Feature §9 risk 5 flags the honest-outcome fork: `SingleWriterReplication` ships
 * EXPLICIT/orchestrated designation only (its own KDoc, `SingleWriterReplication.kt`: "this
 * ticket ships EXPLICIT/orchestrated designation only — `designateLeader` is the manual-failover
 * hook the spec declares the default"), so if explicit-only failover admits no interleaving that
 * produces a split-brain window, the measurement is vacuous and the finding is a measured zero.
 *
 * **It is not vacuous. There is a real window, and it is measured below.** The mechanism is
 * `designateLeader`'s scope: a [LeaderMark] is folded into **one peer's** own `leaderMarks` map
 * by a direct call and is *not* gossiped — there is no announcement path between peers for it
 * (contrast `Replication`'s membership, which rides `LocationRegistry.onPublish`). Failing over a
 * two-peer set therefore takes **two** calls, one per peer, and the state between them is a
 * state of the system, not a race: whichever call goes first decides what that state is.
 *
 *  - **Promote-first** (`designateLeader` on the incoming leader, then on the outgoing one):
 *    both instances report `leading == true` for the whole gap. That is the split-brain window
 *    95 §R1 asks for, and the first test measures it.
 *  - **Demote-first** (the outgoing leader first): **no** instance reports `leading == true` for
 *    the gap — the window is zero and the cost has moved to unavailability instead. The second
 *    test measures that.
 *
 * So the measured quantity is a property of the **orchestration order**, not of the kernel, and
 * that is the whole finding: with explicit failover, the split-brain window is exactly as long as
 * the operator leaves it, and one of the two orders makes it zero. Reporting it does not choose
 * between 95 §R1's directions and implements no election ([CHA3-52], [CHA3-84]) — see
 * [NO_ELECTION_DEFINED], asserted at the bottom of this file.
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
 * accepted write lost, none duplicated** — despite one of them being accepted inside the window.
 * The mechanism is asserted mid-test rather than narrated (the successor's catch-up raises the
 * outgoing leader's epoch before its in-window write is stamped, so the write is not fenced), and
 * its limits are stated on `MEASURED_OBSERVED_TOTAL`. A zero here is a result about **this**
 * interleaving; 95 §R1's "in every interleaving" is research-gated and is not answered.
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
     * directory removes a variable rather than hiding one. Each peer still keeps its **own**
     * [SingleWriterReplication], which is what makes the per-peer `leaderMarks` fold, and
     * therefore the window, real.
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

        // The failover, promote-first: the INCOMING leader folds the new mark first.
        set.bReplication.designateLeader(set.epoch1)
        set.drain()
        measurement.tick("designateLeader(epoch=1, peerB) on peerB")

        // Why the in-window write below turns out NOT to be fenced — pinned here rather than
        // narrated, so the explanation is checked: designating the incoming leader runs its
        // `deltaOutlet.linking.onLinked` catch-up, which ships `Stamped(epoch = 1, total)` to the
        // OUTGOING leader. Spec 42's fencing rule raises `currentEpoch` on every delta it does
        // apply, so the outgoing leader — still serving the real write API, since nobody has told
        // it to step down — now stamps its own next write at the NEW epoch, and the successor
        // accepts it. Nothing here is designed or repaired; it is the measured mechanism behind
        // the accounting below.
        assertEquals(1L, set.a.currentEpoch, "the successor's catch-up raised the outgoing leader's epoch")
        assertTrue(set.a.leading, "and the outgoing leader is still serving the real write API")

        // A write issued inside the window, where two instances believe they lead.
        set.issue(set.a)?.let { measurement.acceptedWrite(2, it) }
        measurement.tick("write issued inside the window")

        // The failover completes: the OUTGOING leader folds the same mark and steps down.
        set.aReplication.designateLeader(set.epoch1)
        set.drain()
        measurement.tick("designateLeader(epoch=1, peerB) on peerA")

        set.issue(set.a)?.let { measurement.acceptedWrite(3, it) }
        measurement.tick("post-transition write")

        val report = measurement.report(observedTotal = set.b.total)

        // [CHA3-50] — a MEASURED window, not a vacuous zero. Feature §9 risk 5's other branch.
        assertTrue(report.splitBrainWindow > 0, "explicit-only designation DOES admit a window: ${report.summary()}")
        assertEquals(2, report.splitBrainWindow, report.summary())
        assertEquals(
            listOf(listOf("peerA", "peerB"), listOf("peerA", "peerB")),
            report.splitBrainSamples.map { it.believedLeaders },
            "both instances report leading == true for the whole gap: ${report.summary()}",
        )

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
        assertEquals(1, report.acceptedDuringSplitBrain.size, report.summary())

        // The accounting itself is asserted exactly, whatever it says — see the class KDoc on what
        // it depends on. It is a measurement of this transition, not a property claim about the
        // kernel.
        assertEquals(4, report.accepted.size, report.summary())
        assertEquals(4L, report.expectedTotal, report.summary())
        assertEquals(MEASURED_OBSERVED_TOTAL, report.observedTotal, report.summary())
        assertEquals(MEASURED_LOST, report.lostWrites, report.summary())
        assertEquals(MEASURED_DUPLICATED, report.duplicatedWrites, report.summary())
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

        // The same failover, the other order: the OUTGOING leader steps down first.
        set.aReplication.designateLeader(set.epoch1)
        set.drain()
        measurement.tick("designateLeader(epoch=1, peerB) on peerA")

        set.bReplication.designateLeader(set.epoch1)
        set.drain()
        measurement.tick("designateLeader(epoch=1, peerB) on peerB")

        val report = measurement.report(observedTotal = set.b.total)

        assertEquals(
            0,
            report.splitBrainWindow,
            "no sample ever saw two leaders under this order: ${report.summary()}",
        )
        assertEquals(emptyList(), report.splitBrainSamples, report.summary())

        // The cost moved rather than vanishing: for one orchestration step nobody leads at all.
        val gap = report.samples.singleOrNull { it.believedLeaders.isEmpty() }
        assertNotNull(gap, "the demoted leader stepped down before its successor stepped up: ${report.summary()}")
        assertEquals("designateLeader(epoch=1, peerB) on peerA", report.interleaving[gap.at])

        // No write was issued in the gap, and the pre-transition writes are intact at the
        // successor: nothing is lost by this ordering, on this transition.
        assertEquals(2, report.accepted.size, report.summary())
        assertEquals(0L, report.lostWrites, report.summary())
        assertEquals(0L, report.duplicatedWrites, report.summary())
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
        val fields = LeaderChurnReport::class.java.declaredFields.map { it.name }
        assertTrue(
            fields.none { it.contains("pass", ignoreCase = true) || it.contains("verdict", ignoreCase = true) },
            "LeaderChurnReport must carry measurements only, found: $fields",
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
         * this transition**, even though one of them was accepted while two instances believed
         * they were leading. The reason is the epoch bump asserted mid-test: the successor's
         * catch-up raises the outgoing leader's `currentEpoch` before its in-window write is
         * stamped, so the write ships at the new epoch and is not fenced.
         *
         * The limit of that result, in the file rather than only in a report: it is **one
         * transition, on a two-instance set, with writes issued only at the outgoing leader**,
         * against the reference fencing implementation described in the class KDoc, on a
         * **constructed** interleaving rather than a generated one (class KDoc again).
         *
         * And one more limit, which the phrase "none lost, none duplicated" would otherwise
         * overstate: the accounting is read **at the successor only** — `observedTotal` is the
         * post-transition leader's state and nothing here reads or asserts the DEMOTED
         * instance's. Those two states are not guaranteed to agree after a transition: the
         * successor's `onLinked` catch-up ships its total as a *from-zero* delta, and on a
         * failover that delta's target is an already-populated ex-leader rather than a fresh
         * follower. Whether [CHA3-51]'s "duplicated across the transition" should be accounted
         * at every instance is filed as computenet-yqgd, with the reviewer's measurements; it is
         * deliberately not decided here.
         *
         * None of this is evidence that the split-brain window is harmless in general — the window itself is
         * real and non-zero, and 95 §R1's "prove or refute ... in every interleaving" is a
         * research-gated question this measurement does not answer.
         */
        const val MEASURED_OBSERVED_TOTAL: Long = 4
        const val MEASURED_LOST: Long = 0
        const val MEASURED_DUPLICATED: Long = 0
    }
}
