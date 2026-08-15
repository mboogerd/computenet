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
 * Also owns the per-instance interest-assignment table (spec 40/42
 * §Interest-scoped instance sets, CP-D2) — see [setInterest]/[interestOf].
 *
 * No reference to [ManagedHost], `InvocationSink`, `ParkQueue`, or
 * `LocationRegistry`. Constructible with no arguments and fully
 * unit-testable standing alone.
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

    /**
     * Per-instance [civictech.cell.link.Interest] (spec 40/42 §Interest-scoped
     * instance sets, CP-D2): the demand predicate the gossip linker consults
     * to decide whether a link forms and to filter each emission to the
     * target's interest. Unset ⇒ total interest — every instance wants every
     * delta, so the linker's behavior is byte-identical to pre-interest
     * gossip (the replication default).
     *
     * Retained on unpublish, deliberately (PN-6 no-widening: "an older epoch
     * cannot widen a shed range back", `civictech.cell.replication.InstanceSet`
     * KDoc) — clearing this entry when a ref is unpublished would let a
     * republished ref fall back to [civictech.cell.link.Interest.Total] and
     * silently widen a shed range. The resulting unbounded growth is filed
     * as computenet-2971; not fixed here.
     */
    private val interests = ConcurrentHashMap<CellRef, civictech.cell.link.Interest>()

    /** Declare [ref]'s interest (the interest-assignment table entry, CP-D2/CP-D3). */
    fun setInterest(ref: CellRef, interest: civictech.cell.link.Interest) {
        interests[ref] = interest
    }

    /** [ref]'s declared interest, or [civictech.cell.link.Interest.Total] when unset. */
    fun interestOf(ref: CellRef): civictech.cell.link.Interest =
        interests[ref] ?: civictech.cell.link.Interest.Total
}
