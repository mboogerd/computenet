package civictech.bench.macro.mesh

import civictech.bench.macro.LocalDeliveryCounter
import civictech.cell.durability.Journal
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.link.PeerId
import civictech.cell.replication.Replication
import civictech.cell.wire.Peering
import civictech.testkit.dst.DstWorld

/**
 * The two-host execution shape belonging to one benchmark peer.
 *
 * A peer has one scheduler for its data host and another for its bridge host.
 * Keeping those schedulers separate preserves the same host/bridge boundary as
 * a deployed peer while both remain driven by the mesh's one deterministic
 * controller. The data host is intentionally not declared through
 * [DstWorld.hosts]: that seam supplies the world's shared registry, whereas a
 * mesh peer owns [registry].
 */
class BenchPeer(
    world: DstWorld,
    val index: Int,
    val hostCount: Int,
    journal: Journal?,
    replication: Boolean,
) {
    val name: String = "peer$index"
    val registry: LocationRegistry = LocationRegistry()
    /**
     * Harness-side counter on the data host only. Its snapshot is taken after graph
     * construction, so spawn/link management never enters a propagation point, while
     * the undecorated bridge scheduler keeps transport work in the frame/byte counters.
     */
    val localDeliveries = LocalDeliveryCounter(world.controller.scheduler())
    val host: ManagedHost = ManagedHost(
        scheduler = localDeliveries,
        registry = registry,
        journal = journal,
    )
    val bridgeHost: ManagedHost = ManagedHost(
        scheduler = world.controller.scheduler(),
        registry = registry,
    )
    val side: Peering.Side = Peering.Side(
        registry = registry,
        bridgeHost = bridgeHost,
        peer = PeerId("bench-$index"),
    )
    val replication: Replication? = if (replication) Replication(registry) else null
}
