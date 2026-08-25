package civictech.testkit.dst.churn

import civictech.testkit.dst.ChurnEvent
import civictech.testkit.dst.DepartEvent
import civictech.testkit.dst.DepartureMode
import civictech.testkit.dst.DstCheck
import civictech.testkit.dst.DstOutcome
import civictech.testkit.dst.DstReport
import civictech.testkit.dst.DstRun
import civictech.testkit.dst.DstWorld
import civictech.testkit.dst.JoinEvent
import civictech.testkit.dst.PartitionFault
import civictech.testkit.dst.ReassignEvent
import civictech.testkit.dst.RejoinEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * [CHA3-03] and [CHA3-04]: the four departure modes act on a real replicated mesh, each is
 * labelled per event in the report, and join / depart / rejoin compose with an in-flight CHA1
 * partition.
 *
 * ## What every test here asserts, and why
 *
 * **The exact set of fired fault ids.** A green run whose adversary never fired proves nothing,
 * and CHA1 measured two near-misses of exactly that shape. So every test below compares
 * `report.appliedFaults.filter { it.fired > 0 }.map { it.id }` against the ids it planned —
 * not "at least one fired", not "the run passed".
 *
 * **A kernel-visible consequence.** Coarse, deliberately: `Replication.evict` returned true or
 * false, the departed row was asked to close or not, the host's generation moved, the peer can
 * no longer see any other replica. The full watermark/stability assertions belong to the
 * "departure gates" task and are not duplicated here.
 */
class ChurnMeshTest {

    private companion object {
        /** See [writes]: one replicated write costs tens of controller steps on this mesh. */
        const val WRITE_STRIDE = 50

        /** Generous, because a step is one task: 40 striped writes over three peers drain in a few thousand. */
        const val BUDGET = 20_000
    }

    // ------------------------------------------------------------------------------- fixtures

    private fun config(
        peers: Int,
        stepBudget: Int,
        suspendWindow: Int = 4,
        partitionOverlap: Double = 0.5,
    ) = ChurnConfig(
        peerCount = peers..peers,
        eventCount = 0,
        writeConcurrency = 0.0,
        partitionOverlap = partitionOverlap,
        opScriptLength = 0,
        stepBudget = stepBudget,
        suspendWindow = suspendWindow,
    )

    private fun roster(peers: Int): List<String> = List(peers) { "peer$it" }

    /**
     * [count] writes, round-robined over [peers], one every [stride] steps from step [from].
     *
     * The stride is not cosmetic. A `SimulationController` step runs **one task**, and a single
     * replicated write costs on the order of fifty of them once gossip, the delivered-watermark
     * companion and three peers' link fan-out are counted (measured on this mesh: 58 writes
     * drained in 3102 steps). Writes packed one-per-step would therefore all be issued inside
     * the first sixty steps and the rest of the run would be a drain — leaving every churn event
     * scheduled after step sixty to fire against a mesh with no work in flight, which is the
     * quiescent case these tests exist not to measure. Striding them keeps the workload live
     * across the whole event range.
     */
    private fun writes(peers: List<String>, count: Int, from: Int = 2, stride: Int = WRITE_STRIDE): List<ChurnWrite> =
        (0 until count).map { i -> ChurnWrite(from + i * stride, peers[i % peers.size], i) }

    /** Everyone joins at step 1, so the mesh is whole before any departure. */
    private fun joinsAt(step: Int, peers: List<String>): List<ChurnEvent> =
        peers.mapIndexed { i, peer -> JoinEvent("join-$peer", peer, step) as ChurnEvent } +
            peers.mapIndexed { i, peer -> ReassignEvent("assign-$peer", peer, step, "interest-$i", 1L) }

    /**
     * Execute [plan] on a churn mesh and hand back both the report and the world the check saw.
     *
     * The world is captured through the [DstCheck] because that is the only place a consumer
     * ever sees one — which also makes a null world a *signal*: `DstRun` runs no check on a
     * `BUDGET_EXHAUSTED` run, so a test that finds no world learns the run never quiesced
     * rather than mis-reading an empty observation as a pass.
     */
    private fun execute(
        plan: ChurnPlan,
        budget: Int,
        payload: MeshPayload = MeshPayload.SET,
        check: DstCheck = DstCheck.none,
    ): Pair<DstReport, DstWorld> {
        var captured: DstWorld? = null
        val spec = ChurnMesh.spec(plan, payload = payload, maxPeers = plan.peers.size)
        val report = DstRun(
            graph = spec,
            plan = plan.toFaultPlan(),
            budget = budget,
            check = DstCheck { world ->
                captured = world
                check.verify(world)
            },
        ).execute()
        val world = captured ?: fail(
            "the run never quiesced (${report.outcome}, ${report.steps}/${report.budget} steps), " +
                "so no check ran and nothing was observed",
        )
        return report to world
    }

    private fun firedIds(report: DstReport): Set<String> =
        report.appliedFaults.filter { it.fired > 0 }.map { it.id }.toSet()

    // ----------------------------------------------------------------- determinism, first

    /**
     * `doc/dst-rig.md` §4: the first thing a new rig-driven graph asserts. A mesh whose runs are
     * not reproducible cannot support any of the claims the rest of this file makes.
     */
    @Test
    fun `two runs of one churn plan produce the same trace digest`() {
        val peers = roster(3)
        val cfg = config(peers = 3, stepBudget = 4000)
        val plan = ChurnPlan(
            seed = 11L,
            config = cfg,
            peers = peers,
            events = joinsAt(1, peers) + listOf(
                DepartEvent("depart-peer1", "peer1", 600, DepartureMode.EVICT_CLEAN),
                RejoinEvent("rejoin-peer1", "peer1", 1200),
            ),
            writeSchedule = writes(peers, count = 40),
        )
        val spec = ChurnMesh.spec(plan, maxPeers = peers.size)

        val report = DstRun(spec, plan.toFaultPlan(), budget = BUDGET).assertDeterministic(runs = 2)

        assertEquals(DstOutcome.PASSED, report.outcome, report.summary())
        assertEquals(plan.events.map { it.id }.toSet(), firedIds(report), report.summary())
    }

    // ------------------------------------------------------- the crash-and-rebuild premise

    /**
     * The premise the whole executor was gated on: a peer that keeps its OWN `LocationRegistry`
     * — rather than the one `DstWorld` offers through `HostBuildContext` — still rebuilds
     * cleanly through the rig's seam-2 crash, and can rejoin the mesh at the same `CellRef`
     * afterwards.
     *
     * It was `unverified:` in the task's own design, with the fallback of constraining
     * CRASH_UNCLEAN to world-registry hosts. It holds: the rebuild lambda takes only
     * `ctx.scheduler`, the registry and the bridge survive, and the rejoined replica publishes
     * under the ref it always had — peer0 still sees exactly three replicas, not four.
     */
    @Test
    fun `a crashed peer rebuilds on its own registry and rejoins at the same CellRef`() {
        val peers = roster(3)
        val plan = ChurnPlan(
            seed = 5L,
            config = config(peers = 3, stepBudget = 4000),
            peers = peers,
            events = joinsAt(1, peers) + listOf(
                DepartEvent("depart-peer2", "peer2", 600, DepartureMode.CRASH_UNCLEAN),
                RejoinEvent("rejoin-peer2", "peer2", 1200),
            ),
            writeSchedule = writes(peers, count = 40),
        )

        val (report, world) = execute(plan, budget = BUDGET)

        assertEquals(DstOutcome.PASSED, report.outcome, report.summary())
        assertEquals(plan.events.map { it.id }.toSet(), firedIds(report), report.summary())

        val peer2 = MeshPeers.require(world, "peer2")
        assertEquals(1, peer2.crashGeneration, "the crash discarded and rebuilt the host exactly once")
        assertEquals(DepartureMode.CRASH_UNCLEAN, peer2.lastDeparture)
        assertNull(peer2.lastEvictDespawned, "a crash bypasses Replication.evict entirely")
        assertTrue(peer2.member, "the rejoin re-established membership on the rebuilt host")
        assertNotNull(peer2.foldSnapshot(), "the rebuilt peer holds a live replica")

        // The rejoin reused the ref rather than minting one: a fresh CellRef would have left a
        // fourth instance of the logical id published on peer0's registry.
        val visible = MeshPeers.require(world, "peer0").visibleReplicas()
        assertEquals(3, visible.size, "peer0 sees one replica per peer, not one per join: $visible")
        assertEquals(setOf(0L, 1L, 2L), visible.map { it.instanceId }.toSet())
        assertTrue(peer2.ref in visible, "the rejoined replica republished under its original ref")
    }

    // ------------------------------------------------------------------- the four departures

    @Test
    fun `EVICT_CLEAN despawns the replica and asks for the departed row to close`() {
        val (report, world) = departureRun("peer1", DepartureMode.EVICT_CLEAN)

        assertEquals(DstOutcome.PASSED, report.outcome, report.summary())
        val peer1 = MeshPeers.require(world, "peer1")
        assertEquals(DepartureMode.EVICT_CLEAN, peer1.lastDeparture)
        assertEquals(true, peer1.lastEvictDespawned, "a reachable peer remained, so evict despawned")
        assertEquals(true, peer1.lastEvictClosedRow)
        assertFalse(peer1.member)
        assertTrue(
            report.appliedFaults.single { it.id == "depart-peer1" }.description.contains("EVICT_CLEAN"),
            "the report labels the mode per departure",
        )
    }

    @Test
    fun `EVICT_NO_CLOSE despawns the replica and leaves the departed row open`() {
        val (report, world) = departureRun("peer1", DepartureMode.EVICT_NO_CLOSE)

        assertEquals(DstOutcome.PASSED, report.outcome, report.summary())
        val peer1 = MeshPeers.require(world, "peer1")
        assertEquals(DepartureMode.EVICT_NO_CLOSE, peer1.lastDeparture)
        assertEquals(true, peer1.lastEvictDespawned)
        assertEquals(false, peer1.lastEvictClosedRow, "the PN-0c control seam was exercised")
        assertFalse(peer1.member)
        assertTrue(
            report.appliedFaults.single { it.id == "depart-peer1" }.description.contains("EVICT_NO_CLOSE"),
            "the report labels the mode per departure",
        )
    }

    @Test
    fun `CRASH_UNCLEAN discards the host without reaching Replication evict`() {
        val (report, world) = departureRun("peer1", DepartureMode.CRASH_UNCLEAN)

        assertEquals(DstOutcome.PASSED, report.outcome, report.summary())
        val peer1 = MeshPeers.require(world, "peer1")
        assertEquals(DepartureMode.CRASH_UNCLEAN, peer1.lastDeparture)
        assertEquals(1, peer1.crashGeneration)
        assertNull(peer1.lastEvictDespawned, "no eviction ran: nothing was announced or drained")
        assertFalse(peer1.member)
        assertTrue(
            report.appliedFaults.single { it.id == "depart-peer1" }.description.contains("CRASH_UNCLEAN"),
            "the report labels the mode per departure",
        )
    }

    @Test
    fun `PARTITION_SUSPEND parks the peer's links until no other replica is reachable`() {
        val (report, world) = departureRun("peer1", DepartureMode.PARTITION_SUSPEND)

        assertEquals(DstOutcome.PASSED, report.outcome, report.summary())
        val peer1 = MeshPeers.require(world, "peer1")
        assertEquals(DepartureMode.PARTITION_SUSPEND, peer1.lastDeparture)
        assertTrue(peer1.suspended)
        assertEquals(0, peer1.reachablePeers(), "replicasOf(id) − {local} is empty, which is what evict gates on")
        assertNull(peer1.lastEvictDespawned, "a suspension is not an eviction")
        assertTrue(peer1.member, "the peer never left: it is behind a partition, and can come back")
        assertTrue(
            report.appliedFaults.single { it.id == "depart-peer1" }.description.contains("PARTITION_SUSPEND"),
            "the report labels the mode per departure",
        )
    }

    private fun departureRun(peer: String, mode: DepartureMode): Pair<DstReport, DstWorld> {
        val peers = roster(3)
        val plan = ChurnPlan(
            seed = 7L,
            config = config(peers = 3, stepBudget = 4000),
            peers = peers,
            events = joinsAt(1, peers) + listOf(DepartEvent("depart-$peer", peer, 600, mode)),
            writeSchedule = writes(peers, count = 40),
        )
        val (report, world) = execute(plan, budget = BUDGET)
        assertEquals(plan.events.map { it.id }.toSet(), firedIds(report), report.summary())
        return report to world
    }

    // --------------------------------------------------------- [CHA3-04]: composition with CHA1

    /**
     * [CHA3-04]: a join, a departure and a rejoin all land while a CHA1 `PartitionFault` holds
     * `peer0<->peer1` parked — the composition the pre-declared peer set exists for. The
     * partition names an edge by name, at install time, several steps before the peer at the
     * other end of the churn has even joined.
     */
    @Test
    fun `join depart and rejoin compose with an in-flight CHA1 partition`() {
        val peers = roster(3)
        val cfg = config(peers = 3, stepBudget = 4000)
        val plan = ChurnPlan(
            seed = 3L,
            config = cfg,
            peers = peers,
            events = listOf<ChurnEvent>(
                JoinEvent("join-peer0", "peer0", 1),
                JoinEvent("join-peer1", "peer1", 1),
                JoinEvent("join-peer2", "peer2", 300),
                DepartEvent("depart-peer2", "peer2", 600, DepartureMode.EVICT_CLEAN),
                RejoinEvent("rejoin-peer2", "peer2", 1200),
                ReassignEvent("assign-peer0", "peer0", 1200, "interest-2", 4L),
            ),
            writeSchedule = writes(peers, count = 40),
        ).withFaults(PartitionFault.park("cha1-park", "peer0<->peer1", from = 400, until = 1400))

        val (report, world) = execute(plan, budget = BUDGET)

        assertEquals(DstOutcome.PASSED, report.outcome, report.summary())
        assertEquals(
            plan.events.map { it.id }.toSet() + "cha1-park",
            firedIds(report),
            report.summary(),
        )
        // The park fired at both endpoints — a partition that never healed would leave the run
        // asserting convergence over a still-split mesh.
        assertEquals(
            listOf(400, 1400),
            report.appliedFaults.single { it.id == "cha1-park" }.activationSteps,
        )
        val peer2 = MeshPeers.require(world, "peer2")
        assertTrue(peer2.member, "peer2 rejoined while the partition was still open")
        assertEquals(DepartureMode.EVICT_CLEAN, peer2.lastDeparture)
        assertEquals(4L, MeshPeers.require(world, "peer0").assignedEpoch())
    }

    // ----------------------------------------------------------------------- the PN variant

    /**
     * One mode only, on the PN payload: the four departure modes are each covered above, on the
     * OR-map mesh. What this adds is that the PN_COUNTER payload reaches the same seam — the name
     * previously read as four-mode coverage of this payload, which it has never been.
     */
    @Test
    fun `the PnCounter mesh carries an EVICT_NO_CLOSE departure`() {
        val peers = roster(3)
        val plan = ChurnPlan(
            seed = 9L,
            config = config(peers = 3, stepBudget = 4000),
            peers = peers,
            events = joinsAt(1, peers) + listOf(
                DepartEvent("depart-peer2", "peer2", 600, DepartureMode.EVICT_NO_CLOSE),
            ),
            writeSchedule = writes(peers, count = 40),
        )

        val (report, world) = execute(plan, budget = BUDGET, payload = MeshPayload.PN_COUNTER)

        assertEquals(DstOutcome.PASSED, report.outcome, report.summary())
        assertEquals(plan.events.map { it.id }.toSet(), firedIds(report), report.summary())
        val peer0 = MeshPeers.require(world, "peer0")
        assertTrue(
            (peer0.foldSnapshot() as Long) > 0L,
            "the PN counter mesh actually carried the workload",
        )
        assertEquals(false, MeshPeers.require(world, "peer2").lastEvictClosedRow)
    }
}
