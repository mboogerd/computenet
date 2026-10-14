package civictech.bench.macro.mesh

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.Replicable
import civictech.cell.data.SetOps
import civictech.cell.data.delta.SetDelta
import civictech.cell.durability.Journal
import civictech.cell.graph.ConnectStep
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.SpawnStep
import civictech.cell.host.DeadLetter
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.SimulationController
import civictech.cell.link.LinkResult
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.replication.WriteAuthority
import civictech.cell.wire.Peering
import civictech.testkit.dst.DstWorld
import java.util.concurrent.RejectedExecutionException

/** Routed stand-in for a set-delta port named `inlet`. */
interface SetDeltaInletProxy {
    val inlet: Use<Propagate<SetDelta<Long>>>
}

/** Routed stand-in for a set-delta port named `left`. */
interface SetDeltaLeftProxy {
    val left: Use<Propagate<SetDelta<Long>>>
}

/** Routed stand-in for a set-delta port named `right`. */
interface SetDeltaRightProxy {
    val right: Use<Propagate<SetDelta<Long>>>
}

/** Routed write surface for a benchmark source [civictech.cell.data.SetCell]. */
interface SetOpsProxy {
    val inlet: Use<SetOps<Long>>
}

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
    private val replicationEnabled: Boolean,
    private val capturedDeadLetters: List<DeadLetter>,
) {
    private data class SpawnPlan(
        val step: SpawnStep,
        val replicated: Boolean,
        val placement: Int?,
    )

    private data class AppliedCell(
        val replicated: Boolean,
        val placement: Int,
        val refsByPeer: Map<Int, CellRef>,
        val cellsByPeer: Map<Int, Cell>,
    ) {
        val primaryRef: CellRef get() = refsByPeer.getValue(if (replicated) 0 else placement)
    }

    private val appliedCells = linkedMapOf<String, AppliedCell>()
    private var appliedOnce = false

    /**
     * Dead letters emitted so far by this mesh's data and bridge hosts, in
     * arrival order. Each read is a fresh snapshot of the live capture.
     */
    val deadLetters: List<DeadLetter> get() = capturedDeadLetters.toList()

    /** Drain every peer scheduler through the mesh's deterministic controller. */
    fun runToIdle(budget: Int = SimulationController.DEFAULT_BUDGET): Int =
        world.controller.runToIdle(budget)

    /**
     * Apply one placed graph to this mesh.
     *
     * A mesh is deliberately one-shot: a second call is refused even if the
     * first call failed after spawning began. All spawns are performed before
     * links, then the controller is drained once so peer announcements are
     * visible before any routed link is installed. The caller still owns the
     * post-write [runToIdle] drive.
     *
     * A cross-host link needs a loopback between its two peers: announcements
     * are not relayed, so under [MeshTopology.STAR] two spokes never resolve
     * each other's refs and a spoke-to-spoke link is refused rather than left
     * to park every delta. (Replicated sources are unaffected: their consumer
     * links are local and gossip does travel through the hub.)
     *
     * The graph's [civictech.cell.graph.IdentityBinding] is intentionally
     * ignored. Mesh refs are always the stable [MeshRefs] function of handle,
     * peer index, and host count.
     */
    fun apply(spec: GraphSpec): Map<String, CellRef> {
        check(!appliedOnce) { "BenchMesh.apply may only be called once" }

        val plans = linkedMapOf<String, SpawnPlan>()
        val connects = mutableListOf<ConnectStep>()
        for (step in spec.lowered()) {
            when (step) {
                is SpawnStep -> {
                    val replicated = replicationEnabled && "source" in step.handle
                    validateSpawn(step, replicated)
                    val placement = if (replicated) null else placementOf(step)
                    require(plans.put(step.handle, SpawnPlan(step, replicated, placement)) == null) {
                        "duplicate spawn handle '${step.handle}'"
                    }
                }

                is ConnectStep -> connects += step
                else -> throw IllegalArgumentException("unsupported graph step ${step.javaClass.simpleName}")
            }
        }
        connects.forEach { validateConnect(it, plans) }
        appliedOnce = true

        for ((handle, plan) in plans) {
            appliedCells[handle] = if (plan.replicated) {
                spawnReplicas(plan.step)
            } else {
                spawnPlaced(plan.step, plan.placement!!)
            }
        }

        runToIdle()
        connects.forEach(::connect)
        return appliedCells.mapValues { it.value.primaryRef }
    }

    /** The stable ref of a replicated [handle] on [peer]. */
    fun replicaRef(handle: String, peer: Int): CellRef {
        requirePeer(peer)
        val record = appliedCell(handle)
        require(record.replicated) { "handle '$handle' is not replicated" }
        return record.refsByPeer.getValue(peer)
    }

    /**
     * A routed source write API. For a non-replicated source, [peer] must be
     * exactly its declared placement; replicated sources accept every peer.
     */
    fun sourceInlet(handle: String, peer: Int = 0): SetOps<Long> {
        val ref = refAt(handle, peer)
        return (HostedCellProxy.create(ref, peers[peer].registry, SetOpsProxy::class.java) as SetOpsProxy)
            .inlet.call
    }

    /** The live cell for [handle], using its placement (or replica zero). */
    fun cell(handle: String): Cell {
        val record = appliedCell(handle)
        val peer = if (record.replicated) 0 else record.placement
        return record.cellsByPeer.getValue(peer)
    }

    /** The live cell for [handle] at the explicitly selected [peer]. */
    fun cell(handle: String, peer: Int): Cell {
        refAt(handle, peer)
        return appliedCell(handle).cellsByPeer.getValue(peer)
    }

    private fun validateSpawn(step: SpawnStep, replicatedByMesh: Boolean) {
        when {
            step.family != null -> refuseSpawn(step, "declares family")
            step.inputs.isNotEmpty() -> refuseSpawn(step, "declares inputs")
            step.authority != WriteAuthority.Open -> refuseSpawn(step, "declares non-Open authority")
            step.replicated -> refuseSpawn(step, "declares replicated=true; replication is a mesh switch")
            step.journalId != null -> refuseSpawn(step, "declares journalId")
            step.shadow -> refuseSpawn(step, "declares shadow=true")
            !replicatedByMesh -> placementOf(step)
        }
    }

    private fun validateConnect(step: ConnectStep, plans: Map<String, SpawnPlan>) {
        val from = plans[step.from]
            ?: throw IllegalArgumentException("connect names unknown source handle '${step.from}'")
        val to = plans[step.to]
            ?: throw IllegalArgumentException("connect names unknown target handle '${step.to}'")
        require(!to.replicated) { "connect target '${step.to}' is replicated" }

        val sourcePeer = if (from.replicated) to.placement!! else from.placement!!
        val targetPeer = to.placement!!
        if (sourcePeer == targetPeer) return
        if (step.inlet !in ROUTED_DELTA_INLETS) {
            throw IllegalArgumentException(
                "cross-host target '${step.to}' uses unsupported inlet '${step.inlet}'",
            )
        }
        val edgeName = "peer${minOf(sourcePeer, targetPeer)}<->peer${maxOf(sourcePeer, targetPeer)}"
        if (edgeName !in loopbacks) {
            throw IllegalArgumentException(
                "cross-host link '${step.from}' (peer$sourcePeer) → '${step.to}' (peer$targetPeer) " +
                    "has no loopback under $topology: peer$sourcePeer never resolves the target, " +
                    "so every delta would park undelivered",
            )
        }
    }

    private fun spawnPlaced(step: SpawnStep, peer: Int): AppliedCell {
        val ref = MeshRefs.ref(step.handle, peer, hostCount)
        val cell = step.factory.create(ref)
        requireFactoryRef(step, ref, cell)
        peers[peer].host.managementInlet.call.spawn(cell)
        return AppliedCell(
            replicated = false,
            placement = peer,
            refsByPeer = mapOf(peer to ref),
            cellsByPeer = mapOf(peer to cell),
        )
    }

    private fun spawnReplicas(step: SpawnStep): AppliedCell {
        val refs = linkedMapOf<Int, CellRef>()
        val cells = linkedMapOf<Int, Cell>()
        for (peerIndex in peers.indices) {
            val ref = MeshRefs.ref(step.handle, peerIndex, hostCount)
            val cell = step.factory.create(ref)
            requireFactoryRef(step, ref, cell)
            if (cell !is Replicable<*>) {
                throw IllegalArgumentException(
                    "spawn '${step.handle}' is selected for replication but ${cell.javaClass.name} is not Replicable",
                )
            }
            peers[peerIndex].replication!!.replicate(cell, peers[peerIndex].host)
            refs[peerIndex] = ref
            cells[peerIndex] = cell
        }
        return AppliedCell(
            replicated = true,
            placement = 0,
            refsByPeer = refs,
            cellsByPeer = cells,
        )
    }

    private fun connect(step: ConnectStep) {
        val from = appliedCell(step.from)
        val to = appliedCell(step.to)
        val targetPeer = to.placement
        val sourcePeer = if (from.replicated) targetPeer else from.placement
        val fromRef = from.refsByPeer.getValue(sourcePeer)
        val toRef = to.refsByPeer.getValue(targetPeer)

        if (sourcePeer == targetPeer) {
            val result = peers[sourcePeer].host.managementInlet.call.connect(
                fromRef,
                step.outlet,
                toRef,
                step.inlet,
                step.options,
            )
            check(result !is LinkResult.Rejected) {
                "link ${step.from}.${step.outlet} → ${step.to}.${step.inlet} rejected: " +
                    (result as LinkResult.Rejected).reason
            }
        } else {
            val routed = routedDeltaInlet(toRef, sourcePeer, step.inlet)
            peers[sourcePeer].host.managementInlet.call.connect(
                fromRef,
                step.outlet,
                Use.fixed(routed, PortRef.generate()),
            )
        }
    }

    private fun routedDeltaInlet(toRef: CellRef, sourcePeer: Int, inlet: String): Propagate<SetDelta<Long>> =
        when (inlet) {
            "inlet" -> (HostedCellProxy.create(
                toRef,
                peers[sourcePeer].registry,
                SetDeltaInletProxy::class.java,
            ) as SetDeltaInletProxy).inlet.call

            "left" -> (HostedCellProxy.create(
                toRef,
                peers[sourcePeer].registry,
                SetDeltaLeftProxy::class.java,
            ) as SetDeltaLeftProxy).left.call

            "right" -> (HostedCellProxy.create(
                toRef,
                peers[sourcePeer].registry,
                SetDeltaRightProxy::class.java,
            ) as SetDeltaRightProxy).right.call

            else -> error("validated routed inlet '$inlet' was not recognized")
        }

    private fun refAt(handle: String, peer: Int): CellRef {
        requirePeer(peer)
        val record = appliedCell(handle)
        if (!record.replicated) {
            require(peer == record.placement) {
                "handle '$handle' is placed on peer ${record.placement}, not peer $peer"
            }
        }
        return record.refsByPeer.getValue(peer)
    }

    private fun appliedCell(handle: String): AppliedCell =
        appliedCells[handle] ?: throw IllegalArgumentException("unknown applied handle '$handle'")

    private fun requirePeer(peer: Int) {
        require(peer in peers.indices) { "peer $peer is outside 0 until $hostCount" }
    }

    private fun placementOf(step: SpawnStep): Int {
        val placement = step.placement
            ?: throw IllegalArgumentException("spawn '${step.handle}' has null placement")
        val index = HOST_PLACEMENT.matchEntire(placement)?.groupValues?.get(1)?.toIntOrNull()
        if (index == null || index !in peers.indices) {
            throw IllegalArgumentException(
                "spawn '${step.handle}' has placement '$placement' outside host0..host${hostCount - 1}",
            )
        }
        return index
    }

    private fun requireFactoryRef(step: SpawnStep, expected: CellRef, cell: Cell) {
        require(cell.ref == expected) {
            "spawn '${step.handle}' factory returned ref ${cell.ref}, expected $expected"
        }
    }

    private fun refuseSpawn(step: SpawnStep, reason: String): Nothing =
        throw IllegalArgumentException("spawn '${step.handle}' $reason")

    companion object {
        private val HOST_PLACEMENT = Regex("host(\\d+)")
        private val ROUTED_DELTA_INLETS = setOf("inlet", "left", "right")

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
                        replicationEnabled = replication,
                        capturedDeadLetters = deadLetters,
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
