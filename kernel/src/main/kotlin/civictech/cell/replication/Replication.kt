package civictech.cell.replication

import civictech.cell.CellRef
import civictech.cell.data.Propagate
import civictech.cell.data.Replicable
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.port.FanOutlet
import civictech.cell.port.Use
import civictech.cell.port.streamTo
import civictech.cell.proxy.HostedCellProxy
import java.util.*

/**
 * Replica wiring (spec 42, G-7, M7.3). A replica is an instance of the same
 * logical cell — equal `CellRef.id`, distinct `instanceId` (G-8) — on some
 * host, possibly behind another registry. Every peer runs the same local
 * rule: *link each of MY replicas' delta outlets to every other replica's
 * `deltaInlet` I learn about* — so the full gossip mesh emerges symmetrically
 * from ordinary announcements, with no coordinator and no second sync
 * protocol: delta links are ordinary links over routed proxies, late-join
 * catch-up doubles as initial sync, and park/replay + tag idempotence double
 * as anti-entropy after partitions (M7.4).
 *
 * Only cells of the mergeable class replicate — [Replicable] is the contract
 * (session delta 4): the tagged set family (tombstoned OR-set) and the
 * per-source [civictech.cell.data.PnCounterCell]. Plain CounterCell does not
 * qualify (addition is not idempotent — echoes would double-count).
 *
 * ponytail: link wiring calls streamTo on the local outlet directly (same
 * ceiling as the M5.7 streamTo idiom — fine single-threaded/simulated;
 * production wiring wants a host-queue hop).
 */
class Replication(private val registry: LocationRegistry) {

    interface ReplicaDeltaInlet {
        val deltaInlet: Use<Propagate<Any?>>
    }

    private val localReplicas = mutableMapOf<UUID, MutableList<Replicable<*>>>()
    private val linked = mutableSetOf<Pair<CellRef, CellRef>>()

    init {
        registry.onPublish { ref -> linkOut(ref) }
    }

    /**
     * Spawn [cell] on [host] as a replica: gossip links to every currently
     * known replica of its logical id are installed now, and to future ones
     * as their announcements arrive. The caller owns instance-id uniqueness
     * (distinct per replica, minted without coordination).
     */
    fun replicate(cell: Replicable<*>, host: ManagedHost) {
        localReplicas.getOrPut(cell.ref.id) { mutableListOf() } += cell
        host.managementInlet.call.spawn(cell)
        registry.replicasOf(cell.ref.id).forEach { other -> maybeLink(cell, other) }
    }

    private fun linkOut(newRef: CellRef) {
        localReplicas[newRef.id]?.forEach { local -> maybeLink(local, newRef) }
    }

    private fun maybeLink(local: Replicable<*>, other: CellRef) {
        if (other == local.ref) return
        if (!linked.add(local.ref to other)) return
        // the proxy resolves the port by name; delta types are erased on this
        // path and re-checked at the receiving inlet's serve
        val routed = (HostedCellProxy.create(other, registry, ReplicaDeltaInlet::class.java)
                as ReplicaDeltaInlet).deltaInlet.call
        @Suppress("UNCHECKED_CAST")
        (local.outlet as FanOutlet<Propagate<Any?>>).streamTo(routed)
    }
}
