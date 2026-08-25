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
}
