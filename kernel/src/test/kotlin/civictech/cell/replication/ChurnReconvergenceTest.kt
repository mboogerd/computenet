package civictech.cell.replication

import civictech.testkit.dst.DepartEvent
import civictech.testkit.dst.DepartureMode
import civictech.testkit.dst.DstCheck
import civictech.testkit.dst.DstOutcome
import civictech.testkit.dst.DstRun
import civictech.testkit.dst.JoinEvent
import civictech.testkit.dst.ReassignEvent
import civictech.testkit.dst.churn.AcceptedOps
import civictech.testkit.dst.churn.BatchReference
import civictech.testkit.dst.churn.ChurnConfig
import civictech.testkit.dst.churn.ChurnMesh
import civictech.testkit.dst.churn.ChurnPlan
import civictech.testkit.dst.churn.ChurnWrite
import civictech.testkit.dst.churn.MeshConvergences
import civictech.testkit.dst.churn.MeshPayload
import civictech.testkit.dst.churn.MeshPeers
import civictech.testkit.dst.churn.ReconvergenceCheck
import civictech.testkit.dst.churn.ReferenceFold
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The kernel-side smoke of the CHA3 churn harness: one seeded plan, end to end, through
 * [ReconvergenceCheck] — so `:kernel`'s own gate exercises the harness that makes claims about
 * `:kernel`'s replication.
 *
 * ## What this test is for, and what it is not
 *
 * It is **not** a second copy of the testkit suite. The named cases BS-1/BS-2/BS-7/BS-8 live in
 * `civictech.testkit.dst.churn.ReconvergenceCheckTest`, where the harness lives. What this file
 * pins is the seam the harness rests on: a churn plan whose orderly eviction runs against
 * `Replication.evict` in this module leaves the survivors converged
 * ([CHA3-10]) on exactly the operations they accepted ([CHA3-11]), with the departed replica
 * dropped from `LocationRegistry.replicasOf` and therefore excluded from the judgement rather
 * than counted as divergence ([CHA3-13], `[42-REPL-06]`).
 *
 * The single orderly departure is already a conformance scenario —
 * `concord/corpus/42-replication/42-REPL-DEPART-01.yaml` (`covers: [42-REPL-06]`) — and this test
 * deliberately neither duplicates nor replaces it: the scenario states the requirement, this
 * states that the generative harness reaches it under a running workload.
 *
 * **The exact set of fired fault ids is asserted**, because a green run whose adversary never
 * fired proves nothing — measured twice in this feature already.
 */
class ChurnReconvergenceTest {

    @Test
    fun `a seeded churn plan reconverges on the batch reference with the departed fold excluded`() {
        val peers = listOf("peer0", "peer1", "peer2")
        val config = ChurnConfig(
            peerCount = 3..3,
            eventCount = 0,
            departureWeights = mapOf(DepartureMode.EVICT_CLEAN to 1),
            writeConcurrency = 0.0,
            opScriptLength = 0,
            stepBudget = 2000,
            suspendWindow = 8,
        )
        val plan = ChurnPlan(
            seed = 1234L,
            config = config,
            peers = peers,
            events = peers.map { JoinEvent("join-$it", it, 1) } +
                peers.mapIndexed { i, peer -> ReassignEvent("assign-$peer", peer, 1, "interest-$i", 1L) } +
                listOf(DepartEvent("depart-peer1", "peer1", 600, DepartureMode.EVICT_CLEAN)),
            // Strided: a controller step runs ONE task and a replicated write on this mesh costs
            // tens of them, so writes packed one per step would all land before step sixty and the
            // departure would fire against a quiescent mesh.
            writeSchedule = (0 until 18).map { i -> ChurnWrite(2 + i * 50, peers[i % peers.size], i) },
        )

        val spec = ChurnMesh.spec(plan, payload = MeshPayload.SET, maxPeers = 3, aliveUntil = 1200)
        var captured: civictech.testkit.dst.DstWorld? = null
        val check = ReconvergenceCheck.of(MeshPayload.SET)
        val report = MeshConvergences.observing {
            DstRun(
                graph = spec,
                plan = plan.toFaultPlan(),
                budget = 40_000,
                check = DstCheck { world ->
                    captured = world
                    check.verify(world)
                },
            ).execute()
        }

        assertEquals(
            DstOutcome.PASSED,
            report.outcome,
            "${report.summary()} :: ${report.failingCheck?.error?.suppressed?.joinToString()}",
        )
        assertEquals(
            plan.events.map { it.id }.toSet(),
            report.appliedFaults.filter { it.fired > 0 }.map { it.id }.toSet(),
            "every planned churn event must have fired",
        )

        val world = assertNotNull(captured, "a quiesced run always runs its check")
        val departed = MeshPeers.require(world, "peer1")
        val survivor = MeshPeers.require(world, "peer0")
        assertFalse(departed.member, "peer1 departed")
        assertEquals(true, departed.lastEvictDespawned, "Replication.evict despawned it — two peers were reachable")

        val convergence = assertNotNull(MeshConvergences.of(world, "peer0"))
        assertTrue(departed.ref in convergence.states().keys, "the departed replica's frozen fold is still attached")
        assertFalse(
            departed.ref in survivor.registry.replicasOf(survivor.ref.id),
            "an evicted replica leaves replicasOf — that is the departed-stream rule's input",
        )
        assertTrue(convergence.converged(), "the survivors converge with the departed stream excluded")

        val reference = BatchReference.of(world, MeshPayload.SET).foldOf(peers.toSet())
        assertEquals(
            reference,
            MeshConvergences.project(assertNotNull(convergence.state(survivor.ref))),
            "the converged fold is the batch reference over the accepted operations, not merely peer agreement",
        )
        assertTrue(
            AcceptedOps.of(world).any { it.peer == "peer1" },
            "peer1 must have accepted work before it left, or the exclusion tests nothing",
        )
    }

    /**
     * The boundary of `Replication.evict`'s final push-catch-up, measured rather than assumed
     * (computenet-9c5t): a write issued **one controller step** before an `EVICT_CLEAN` does not
     * reach the survivors, and a write issued a hundred steps before one does.
     *
     * ## Why this is a boundary and not a lost-write bug of the catch-up
     *
     * `evict`'s catch-up is documented best-effort, and it is easy to read the loss as the
     * catch-up firing too early — `fireLinked` runs inline in `evict`'s own frame while
     * `HostManagementApi.suspend`/`despawn` are queued, so the catch-up is *not* in fact
     * drain-gated. That ordering wart is real. It is **not** what loses the element, and this
     * test is what says so: the racing write is absent from the DEPARTING replica's own frozen
     * fold, so it was never applied to peer1 at all and no gating of the catch-up could have
     * carried it.
     *
     * The mechanism is the host's scheduling bands. A data send stages at send time and enqueues
     * its dispatch at priority 20 (`ManagedHost.enqueueHostedInvocation`); a management call
     * enqueues at priority 0 (`HostManagementApi`'s dispatch), and `HostScheduler.submit`'s
     * contract is ascending priority, then FIFO. So `evict`'s queued `suspend` **preempts** every
     * already-accepted-but-undispatched local write: `deliver` finds the cell's `ParkQueue`
     * installed and parks it, and the queued `despawn` tears that queue down into dead letters
     * (`ManagedHost.clearSupervision`, counted as `parkedDrainedOnTeardown`) — accounted for, but
     * never applied to the cell. `evict` is therefore a gated *stop*, not a gated drain, for work accepted at the
     * host intake but not yet applied to the cell.
     *
     * Making that a genuine drain is a host-granularity change to spec 33's drain, not a
     * re-ordering of these three lines — filed as its own item rather than smuggled in here.
     *
     * ## What this pins, so the claim above cannot rot
     *
     *  - the racing element IS in the accepted-op ledger (it was issued while peer1 was a member,
     *    which is what makes `BatchReference`'s permitted arm necessary);
     *  - it is in neither the survivors' fold nor peer1's own frozen fold — dropped at peer1's
     *    intake, not in transit;
     *  - the control element, issued a hundred steps earlier, IS in the survivors' fold — so the
     *    loss is the departure-boundary race and not a general failure to hand off.
     */
    @Test
    fun `a write issued one step before a clean evict is dropped at the departing replica's own intake`() {
        val peers = listOf("peer0", "peer1", "peer2")
        val config = ChurnConfig(
            peerCount = 3..3,
            eventCount = 0,
            departureWeights = mapOf(DepartureMode.EVICT_CLEAN to 1),
            writeConcurrency = 0.0,
            opScriptLength = 0,
            stepBudget = 2000,
            suspendWindow = 8,
        )
        val plan = ChurnPlan(
            seed = 4321L,
            config = config,
            peers = peers,
            events = peers.map { JoinEvent("join-$it", it, 1) } +
                peers.mapIndexed { i, peer -> ReassignEvent("assign-$peer", peer, 1, "interest-$i", 1L) } +
                listOf(DepartEvent("depart-peer1", "peer1", 601, DepartureMode.EVICT_CLEAN)),
            // ordinal 98 is the control (a hundred steps of slack), 99 the racing write.
            writeSchedule = (0 until 12).map { i -> ChurnWrite(2 + i * 50, peers[i % peers.size], i) } +
                listOf(ChurnWrite(500, "peer1", 98), ChurnWrite(600, "peer1", 99)),
        )

        val spec = ChurnMesh.spec(plan, payload = MeshPayload.SET, maxPeers = 3, aliveUntil = 1200)
        var captured: civictech.testkit.dst.DstWorld? = null
        val report = MeshConvergences.observing {
            DstRun(
                graph = spec,
                plan = plan.toFaultPlan(),
                budget = 40_000,
                check = DstCheck { world -> captured = world },
            ).execute()
        }

        val world = assertNotNull(captured, "a quiesced run always runs its check (${report.summary()})")
        val departed = MeshPeers.require(world, "peer1")
        val survivor = MeshPeers.require(world, "peer0")
        assertFalse(departed.member, "peer1 departed")
        assertEquals(true, departed.lastEvictDespawned, "a clean evict with two reachable peers despawns")

        assertTrue(
            AcceptedOps.of(world).any { it.element == "peer1-99" },
            "the racing write must have been ISSUED while peer1 was still a member, or this test " +
                "is not exercising the boundary at all",
        )

        val convergence = assertNotNull(MeshConvergences.of(world, "peer0"))
        val survivorFold = MeshConvergences.project(assertNotNull(convergence.state(survivor.ref)))
        val departedFold = MeshConvergences.project(
            assertNotNull(convergence.state(departed.ref), "the departed replica's frozen fold is still attached"),
        )
        val survivorElements = (survivorFold as ReferenceFold.Elements).elements
        val departedElements = (departedFold as ReferenceFold.Elements).elements

        assertTrue(
            "peer1-98" in survivorElements,
            "a write a hundred steps ahead of the departure IS handed off; survivors hold $survivorElements",
        )
        assertFalse(
            "peer1-99" in departedElements,
            "the racing write is dropped at peer1's OWN intake — never applied locally, so no gating " +
                "of evict's catch-up could carry it; peer1's frozen fold holds $departedElements",
        )
        assertFalse(
            "peer1-99" in survivorElements,
            "and it therefore never reaches the survivors; survivors hold $survivorElements",
        )
    }
}
