package civictech.cell.host

import civictech.cell.CellRef
import civictech.cell.port.PortRef
import civictech.cell.UuidSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Transport-neutral description of one live, directional topology edge. */
@Serializable
@SerialName("TopologyLink")
data class TopologyLink(
    @Serializable(with = UuidSerializer::class) val id: UUID,
    val from: PortRef,
    val to: PortRef,
) : java.io.Serializable

/** Reverse-topology index used by rare-path orchestration such as promotion. */
class TopologyIndex {
    private val links = ConcurrentHashMap<UUID, TopologyLink>()
    private val byCell = ConcurrentHashMap<CellRef, MutableSet<UUID>>()

    fun linked(link: TopologyLink) {
        val previous = links.put(link.id, link)
        if (previous != null) removeFromCells(previous)
        cellsOf(link).forEach { ref ->
            byCell.computeIfAbsent(ref) { ConcurrentHashMap.newKeySet() }.add(link.id)
        }
    }

    fun unlinked(id: UUID) {
        links.remove(id)?.let(::removeFromCells)
    }

    /** Every inbound or outbound link incident on the full [ref]. */
    fun swapSet(ref: CellRef): Set<TopologyLink> =
        byCell[ref]?.mapNotNullTo(mutableSetOf()) { id -> links[id] } ?: emptySet()

    fun inbound(ref: CellRef): Set<TopologyLink> = swapSet(ref).filterTo(mutableSetOf()) { it.to.cell == ref }
    fun outbound(ref: CellRef): Set<TopologyLink> = swapSet(ref).filterTo(mutableSetOf()) { it.from.cell == ref }
    fun all(): Set<TopologyLink> = links.values.toSet()

    private fun cellsOf(link: TopologyLink): Set<CellRef> = setOfNotNull(link.from.cell, link.to.cell)

    private fun removeFromCells(link: TopologyLink) {
        cellsOf(link).forEach { ref ->
            byCell.computeIfPresent(ref) { _, ids -> ids.apply { remove(link.id) }.takeIf { it.isNotEmpty() } }
        }
    }

    /**
     * Would a `from -> to` edge close a cycle already visible in this index?
     * True exactly when [to] can already reach [from] by following existing
     * outbound edges (spec 10/13 rare-path cycle walk; P2 permits expensive
     * linking). Moved from `ManagedHost` (RS-8.4) — verbatim, only the call
     * shape changed from `wouldCloseCycle(topology, from, to)` to
     * `topology.wouldCloseCycle(from, to)`.
     */
    fun wouldCloseCycle(from: CellRef, to: CellRef): Boolean {
        if (from == to) return true
        val visited = mutableSetOf<CellRef>()
        val stack = ArrayDeque<CellRef>().apply { add(to) }
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            if (!visited.add(current)) continue
            if (current == from) return true
            outbound(current).forEach { link -> link.to.cell?.let(stack::add) }
        }
        return false
    }
}
