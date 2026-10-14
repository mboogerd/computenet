package civictech.bench.macro

import civictech.bench.macro.mesh.BenchMesh
import civictech.bench.macro.mesh.CrossHostEdges
import civictech.bench.macro.mesh.MeshBuild
import civictech.bench.macro.mesh.MeshRefs
import civictech.bench.macro.mesh.MeshTopology
import civictech.cell.data.SetCell
import civictech.cell.host.LocationRegistry
import civictech.testkit.dst.StableRefs
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class MeshConstructionTest {

    @Test
    fun fullMeshWiresEveryPair() {
        for (hostCount in listOf(1, 2, 3, 5, 8)) {
            val mesh = built(hostCount, MeshTopology.FULL_MESH)
            mesh.peers.size shouldBe hostCount
            mesh.peers.map { it.registry }.toSet().size shouldBe hostCount
            mesh.peers.map { it.host }.toSet().size shouldBe hostCount
            mesh.peers.map { it.bridgeHost }.toSet().size shouldBe hostCount
            mesh.loopbacks.size shouldBe hostCount * (hostCount - 1) / 2
            mesh.world.edges.names() shouldBe expectedEdges(hostCount, MeshTopology.FULL_MESH)
        }
    }

    @Test
    fun starTouchesPeerZeroOnly() {
        val mesh = built(5, MeshTopology.STAR)

        mesh.loopbacks.size shouldBe 4
        mesh.world.edges.names().all { it.startsWith("peer0<->") } shouldBe true
    }

    @Test
    fun singleHostHasNoLoopbacksAndReportsNotPresent() {
        val single = built(1, MeshTopology.FULL_MESH)
        single.loopbacks.isEmpty() shouldBe true
        single.world.edges.names().shouldBeEmpty()
        single.crossHostEdges shouldBe CrossHostEdges.NotPresentAtThisCoordinate

        val pair = built(2, MeshTopology.FULL_MESH)
        pair.crossHostEdges shouldBe CrossHostEdges.Present(setOf("peer0<->peer1"))
    }

    @Test
    fun everyPeerResolvesEveryOtherPeersRefs() {
        for (hostCount in listOf(3, 5)) {
            val mesh = built(hostCount, MeshTopology.FULL_MESH)
            mesh.peers.forEachIndexed { index, peer ->
                peer.host.managementInlet.call.spawn(SetCell<Long>(MeshRefs.ref("probe", index, hostCount)))
            }
            mesh.runToIdle()

            mesh.peers.forEachIndexed { sourceIndex, _ ->
                val ref = MeshRefs.ref("probe", sourceIndex, hostCount)
                mesh.peers.forEachIndexed { targetIndex, target ->
                    if (sourceIndex != targetIndex) {
                        target.registry.location(ref).shouldBeInstanceOf<LocationRegistry.Remote>()
                    }
                }
            }
            mesh.deadLetters.shouldBeEmpty()
        }
    }

    @Test
    fun meshRefsIsPureAndRoleFree() {
        MeshRefs.ref("a", 2, 5) shouldBe MeshRefs.ref("a", 2, 5)
        MeshRefs.ref("a", 2, 5) shouldBe MeshRefs.ref("a", 2, 5)
        (MeshRefs.ref("a", 2, 5) != MeshRefs.ref("b", 2, 5)) shouldBe true
        (MeshRefs.ref("a", 2, 5) != MeshRefs.ref("a", 3, 5)) shouldBe true
        (MeshRefs.ref("a", 2, 5) != MeshRefs.ref("a", 2, 6)) shouldBe true
        MeshRefs.ref("a", 2, 5).instanceId shouldBe 2L
        MeshRefs.ref("a", 2, 5) shouldBe StableRefs("bench:5", 2).ref("a")
    }

    @Test
    fun unschedulableConstructionReturnsTypedResultAndDoesNotFallBack() {
        val unschedulable = BenchMesh.build(
            hostCount = 5,
            seed = 1,
            topology = MeshTopology.FULL_MESH,
            journalFor = { null },
            replication = false,
            beforePeer = { if (it == 3) throw OutOfMemoryError("simulated") },
        )
        unschedulable shouldBe MeshBuild.Unschedulable(
            5,
            "java.lang.OutOfMemoryError: simulated",
            3,
        )

        val recovered = built(5, MeshTopology.FULL_MESH)
        recovered.peers.size shouldBe 5
    }

    private fun built(hostCount: Int, topology: MeshTopology): BenchMesh =
        (BenchMesh.build(hostCount, 1, topology, { null }, false) as MeshBuild.Built).mesh

    private fun expectedEdges(hostCount: Int, topology: MeshTopology): Set<String> =
        topology.edges(hostCount).map { (i, j) -> "peer$i<->peer$j" }.toSet()
}
