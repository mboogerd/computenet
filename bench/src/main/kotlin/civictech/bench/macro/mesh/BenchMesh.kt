package civictech.bench.macro.mesh

import civictech.cell.Propagate
import civictech.cell.durability.Journal
import civictech.cell.host.DeadLetter
import civictech.cell.host.SimulationController
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.wire.Peering
import civictech.testkit.dst.DstWorld
import java.util.concurrent.RejectedExecutionException

/** Result of attempting to construct a benchmark mesh. */
sealed interface MeshBuild {
    /** A complete mesh with one peer for every requested host. */
    data class Built(val mesh: BenchMesh) : MeshBuild

    /** Construction could not schedule the requested mesh. */
    data class Unschedulable(
        val hostCount: Int,
        val reason: String,
        val observedLimit: Int,
    ) : MeshBuild
}

/** Whether this coordinate contains declared cross-host edges. */
sealed interface CrossHostEdges {
    /** A one-peer coordinate has no cross-host edge surface. */
    data object NotPresentAtThisCoordinate : CrossHostEdges

    /** The named pair edges declared by a multi-peer mesh. */
    data class Present(val edgeNames: Set<String>) : CrossHostEdges
}

/**
 * A deterministic peer mesh used by the macro benchmark fixtures.
 *
 * All loopback frames pass through a named [DstWorld.edges] edge, making the
 * topology observable to the benchmark runner. Data hosts are kept out of
 * [DstWorld.hosts] because each [BenchPeer] has its own location registry.
 */
class BenchMesh private constructor(
    val world: DstWorld,
    val hostCount: Int,
    val topology: MeshTopology,
    val peers: List<BenchPeer>,
    val loopbacks: Map<String, Peering.Loopback>,
    val crossHostEdges: CrossHostEdges,
    deadLetters: List<DeadLetter>,
) {
    /** A snapshot of dead letters emitted by this mesh's data and bridge hosts. */
    val deadLetters: List<DeadLetter> = deadLetters.toList()

    /** Drain every peer scheduler through the mesh's deterministic controller. */
    fun runToIdle(budget: Int = SimulationController.DEFAULT_BUDGET): Int =
        world.controller.runToIdle(budget)

    companion object {
        /**
         * Construct a mesh without falling back to a smaller host count when
         * the requested construction is unschedulable.
         */
        fun build(
            hostCount: Int,
            seed: Long,
            topology: MeshTopology,
            journalFor: (Int) -> Journal?,
            replication: Boolean,
            beforePeer: (Int) -> Unit = {},
        ): MeshBuild {
            require(hostCount >= 1) { "hostCount must be at least 1" }
            val world = DstWorld(seed)
            val peers = mutableListOf<BenchPeer>()
            val loopbacks = linkedMapOf<String, Peering.Loopback>()
            val deadLetters = mutableListOf<DeadLetter>()

            return try {
                for (index in 0 until hostCount) {
                    beforePeer(index)
                    val peer = BenchPeer(
                        world = world,
                        index = index,
                        hostCount = hostCount,
                        journal = journalFor(index),
                        replication = replication,
                    )
                    peers += peer
                    captureDeadLetters(peer.host, deadLetters)
                    captureDeadLetters(peer.bridgeHost, deadLetters)
                }

                for ((i, j) in topology.edges(hostCount)) {
                    val edgeName = "peer$i<->peer$j"
                    world.edges.declare(edgeName, from = "peer$i", to = "peer$j")
                    loopbacks[edgeName] = Peering.loopback(
                        peers[i].side,
                        peers[j].side,
                        interposeAToB = { frame -> world.edges.deliver(edgeName, frame) },
                        interposeBToA = { frame -> world.edges.deliver(edgeName, frame) },
                    )
                }

                MeshBuild.Built(
                    BenchMesh(
                        world = world,
                        hostCount = hostCount,
                        topology = topology,
                        peers = peers.toList(),
                        loopbacks = loopbacks.toMap(),
                        crossHostEdges = if (hostCount == 1) {
                            CrossHostEdges.NotPresentAtThisCoordinate
                        } else {
                            CrossHostEdges.Present(loopbacks.keys.toSet())
                        },
                        deadLetters = deadLetters,
                    ),
                )
            } catch (error: OutOfMemoryError) {
                MeshBuild.Unschedulable(hostCount, reason(error), peers.size)
            } catch (error: RejectedExecutionException) {
                MeshBuild.Unschedulable(hostCount, reason(error), peers.size)
            } catch (error: IllegalStateException) {
                MeshBuild.Unschedulable(hostCount, reason(error), peers.size)
            }
        }

        private fun captureDeadLetters(host: civictech.cell.host.ManagedHost, sink: MutableList<DeadLetter>) {
            host.deadLetterOutlet.subscribe(
                Use.fixed(
                    object : Propagate<DeadLetter> {
                        override fun propagate(value: DeadLetter) {
                            sink += value
                        }
                    },
                    PortRef.generate(),
                ),
            )
        }

        private fun reason(error: Throwable): String =
            "${error.javaClass.name}: ${error.message}"
    }
}
