package civictech.cell.host

import civictech.cell.CellRef
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Instances-by-logical-id index (PN-7 perf cliff): the interest-scoped
 * settlement read ([civictech.cell.replication.Replication.replicaFrontier])
 * calls [instancesOf] once per buffered wave per `recheck`, so a linear scan
 * of every published ref would be quadratic in a large mesh. This index keeps
 * the membership read O(instances-of-one-id). Maintained in lockstep with
 * `LocationRegistry`'s locations map on every install/removal.
 *
 * Owns the membership half only — no reference to [ManagedHost],
 * `InvocationSink`, `ParkQueue`, or `LocationRegistry`. Constructible with no
 * arguments and fully unit-testable standing alone.
 */
class InstanceIndex {

    internal val byLogicalId = ConcurrentHashMap<UUID, MutableSet<CellRef>>()

    fun add(ref: CellRef) {
        byLogicalId.computeIfAbsent(ref.id) { ConcurrentHashMap.newKeySet() }.add(ref)
    }

    fun remove(ref: CellRef) {
        byLogicalId[ref.id]?.let { set -> set.remove(ref); if (set.isEmpty()) byLogicalId.remove(ref.id, set) }
    }

    /**
     * Every published instance (ref) sharing [logicalId] — local and remote (spec
     * 42). Served off the [byLogicalId] index (PN-7): O(instances-of-one-id), not
     * a full scan of every published ref.
     */
    fun instancesOf(logicalId: UUID): Set<CellRef> =
        byLogicalId[logicalId]?.toSet() ?: emptySet()

    /** Every published ref sharing [logicalId] — replicas, local and remote (spec 42). */
    fun replicasOf(logicalId: UUID): Set<CellRef> = instancesOf(logicalId)
}
