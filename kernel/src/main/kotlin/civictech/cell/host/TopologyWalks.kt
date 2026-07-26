package civictech.cell.host

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.control.AttentionBand
import civictech.cell.control.AttentionSupport
import civictech.cell.control.NonSuspendable
import civictech.cell.control.StallNotice
import civictech.cell.link.Linked
import civictech.cell.port.FanInlet
import civictech.cell.port.PolicyTier
import civictech.cell.port.Port
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

/**
 * Session delta 3 (spec 34 decision 3): the unit of attention suspension
 * is the **glitch-free region** — the local downstream wave-frontier join(s)
 * plus their transitive local upstream contributors, bounded by further
 * frontier cells (the frontier, spec 22). Parking one diamond branch would
 * stall waves at the join; parking the whole region cannot. Returns null
 * (veto) if any member is [NonSuspendable] or still attended. A cell with no
 * local downstream join is its own region (per-cell parking, as before).
 * Cross-host region members are invisible here by design — remote branches
 * remain the WAIT/DEGRADE fallback's job.
 *
 * A "join" is any cell carrying a [FanInlet.frontierPolicy] (CP-A4), not a
 * `GlitchFreeCell` specifically — the sugar cell and a plain opt-in cell are
 * treated identically, keying on the policy rather than the class.
 */
internal fun suspensionRegionOf(cells: Map<CellRef, Cell>, cellRef: CellRef): Set<CellRef>? {
    val joins = mutableSetOf<CellRef>()
    bfs(cells, cellRef, downstream = true) { ref, cell ->
        if (hasFrontierPolicy(cell)) {
            joins += ref
            false // the join bounds the walk; regions don't chain through it
        } else true
    }
    if (joins.isEmpty()) return setOf(cellRef)
    val region = mutableSetOf<CellRef>()
    joins.forEach { join ->
        region += join
        bfs(cells, join, downstream = false) { ref, cell ->
            if (hasFrontierPolicy(cell)) false // another region's join: frontier
            else {
                region += ref
                true
            }
        }
    }
    val vetoed = region.any { ref ->
        val cell = cells[ref] ?: return@any false
        cell is NonSuspendable || AttentionSupport.of(cell).band > AttentionBand.NONE
    }
    return if (vetoed) null else region
}

/** A cell is a wave-frontier join iff one of its inlets carries an ALIGN policy (CP-A4, PN-9). */
internal fun hasFrontierPolicy(cell: Cell): Boolean {
    val ports = PortRegistry.of(cell)
    return ports.names().any { name -> (ports[name] as? FanInlet<*>)?.hasPolicy(PolicyTier.ALIGN) == true }
}

/**
 * Local link-graph walk from [start] (exclusive). [visit] returns whether
 * to walk past the visited cell; only cells in [cells] (the host's live
 * set) are reachable. Neighbors resolve by link **port object identity**
 * (the same rule AttentionSupport uses) — PortRefs don't reliably carry
 * their cell.
 */
internal fun bfs(cells: Map<CellRef, Cell>, start: CellRef, downstream: Boolean, visit: (CellRef, Cell) -> Boolean) {
    val portOwner = HashMap<Port, CellRef>()
    cells.forEach { (ref, cell) ->
        val ports = PortRegistry.of(cell)
        ports.names().forEach { name -> ports[name]?.let { portOwner[it] = ref } }
    }
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
