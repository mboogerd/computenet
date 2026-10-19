package civictech.cell.host

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.control.StallNotice
import civictech.cell.link.Linked
import civictech.cell.link.LinkRole
import civictech.cell.port.Port
import civictech.cell.port.PortRef
import civictech.cell.port.PortRegistry
import civictech.cell.protocol.Protocols

/**
 * Local link-topology walks over a host's live [Cell]s, extracted from
 * [ManagedHost] (RS-8.4) — moved next to [TopologyIndex] rather than onto it,
 * since these walk in-process [Port]/[civictech.cell.link.Link] objects
 * directly (the identity-based graph a host actually dispatches over), not
 * [TopologyIndex]'s own transport-neutral [TopologyLink] records; forcing
 * them onto [TopologyIndex] would mean threading a `cells` map through a
 * class that otherwise never needs one. Bodies moved verbatim; the only
 * change is that the host's live `cells` map is now an explicit parameter
 * instead of an implicit closure over `ManagedHost.cells`.
 */

/** One hosted upstream cell and the producer port through which it was first reached. */
class LocalAncestor(val cell: Cell, val viaOutlet: PortRef)

/**
 * Upstream Consume-role ancestry over a host's live link set at the moment of
 * the call. [self] is null when the requested ref is not hosted. [local]
 * excludes [self], while [opaque] contains producer refs whose port object is
 * absent or is not owned by a cell hosted here.
 */
class UpstreamAncestry(
    val self: Cell?,
    val local: Map<CellRef, LocalAncestor>,
    val opaque: Set<PortRef>,
)

/**
 * Walk inbound Consume links from [start] to a local fixpoint. Observe links
 * are never ancestors, and an endpoint outside [cells] is recorded rather than
 * traversed.
 */
internal fun upstreamConsumeAncestors(cells: Map<CellRef, Cell>, start: CellRef): UpstreamAncestry {
    val self = cells[start] ?: return UpstreamAncestry(null, emptyMap(), emptySet())
    val portOwner = portOwners(cells)
    val local = linkedMapOf<CellRef, LocalAncestor>()
    val opaque = linkedSetOf<PortRef>()
    val seen = mutableSetOf(start)
    val frontier = ArrayDeque(listOf(start))

    while (frontier.isNotEmpty()) {
        val current = cells[frontier.removeFirst()] ?: continue
        val ports = PortRegistry.of(current)
        ports.names().forEach { name ->
            val port = ports[name] as? Linked ?: return@forEach
            port.linking.links.forEach { link ->
                if (link.role != LinkRole.Consume || link.toPort !== port) return@forEach
                val ancestorRef = link.fromPort?.let(portOwner::get)
                val ancestor = ancestorRef?.let(cells::get)
                if (ancestorRef == null || ancestor == null) {
                    opaque += link.from
                    return@forEach
                }
                if (!seen.add(ancestorRef)) return@forEach
                local[ancestorRef] = LocalAncestor(ancestor, link.from)
                frontier.addLast(ancestorRef)
            }
        }
    }

    return UpstreamAncestry(self, local, opaque)
}

/**
 * Attention suspension parks one cell at a time. The surrounding topology does
 * not widen the suspension unit; callers apply the ordinary per-cell vetoes.
 */
internal fun suspensionRegionOf(cells: Map<CellRef, Cell>, cellRef: CellRef): Set<CellRef>? = setOf(cellRef)

/**
 * Local link-graph walk from [start] (exclusive). [visit] returns whether
 * to walk past the visited cell; only cells in [cells] (the host's live
 * set) are reachable. Neighbors resolve by link **port object identity**
 * (the same rule AttentionSupport uses) — PortRefs don't reliably carry
 * their cell.
 */
internal fun bfs(cells: Map<CellRef, Cell>, start: CellRef, downstream: Boolean, visit: (CellRef, Cell) -> Boolean) {
    val portOwner = portOwners(cells)
    val seen = mutableSetOf(start)
    val frontier = ArrayDeque(listOf(start))
    while (frontier.isNotEmpty()) {
        val current = cells[frontier.removeFirst()] ?: continue
        val ports = PortRegistry.of(current)
        ports.names().forEach { name ->
            val port = ports[name] as? Linked ?: return@forEach
            port.linking.links.forEach { link ->
                val outbound = link.fromPort === port
                if (outbound != downstream) return@forEach
                val neighborPort = (if (outbound) link.toPort else link.fromPort) ?: return@forEach
                val neighbor = portOwner[neighborPort] // absent: remote — fallback territory
                    ?.takeIf { it != current.ref && seen.add(it) } ?: return@forEach
                val cell = cells[neighbor] ?: return@forEach
                if (visit(neighbor, cell)) frontier.addLast(neighbor)
            }
        }
    }
}

private fun portOwners(cells: Map<CellRef, Cell>): Map<Port, CellRef> {
    val portOwner = HashMap<Port, CellRef>()
    cells.forEach { (ref, cell) ->
        val ports = PortRegistry.of(cell)
        ports.names().forEach { name -> ports[name]?.let { portOwner[it] = ref } }
    }
    return portOwner
}

/** spec 34 decision 3, 20/22 (G-40): typed Stall/Resume notices travel downstream, with data. */
internal fun notifyDownstream(cell: Cell, notice: StallNotice) {
    val ports = PortRegistry.of(cell)
    ports.names().forEach { name ->
        val port = ports[name] as? Linked ?: return@forEach
        port.linking.links.forEach { link ->
            if (link.fromPort === port) Protocols.sendDownstream(link, Protocols.Suspension, notice)
        }
    }
}
