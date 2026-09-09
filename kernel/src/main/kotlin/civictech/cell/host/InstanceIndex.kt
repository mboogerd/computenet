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

    /**
     * Deliberately does NOT clear a [LeaderMark] whose `leaderRef == ref`
     * (f7h.1-D6, epic [MEM1-14]): RESTART/republish keeps leadership, and
     * [leaderMarks] is keyed by [LeaderMark.logicalId], not by ref, so a
     * removal here never touches it. The leaderRef's absence from
     * [replicasOf] afterward is the observation F4's detector reads; the
     * mark itself stays folded until a strictly greater one supersedes it.
     */
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

    /**
     * The [LeaderMark] fold (f7h.1-D1/D2, spec 42 §Single-writer replication;
     * epic computenet-f7h §2.1, C-13): one more announcement kind folded into
     * this membership index, keyed by logical id. Adoption order is TOTAL
     * over `(epoch, leaderRef.instanceId)` (93 I-25 §4.1) — see [ORDER] —
     * unlike the epoch-only, not-total order the fold used before this
     * ticket.
     */
    private val leaderMarks = ConcurrentHashMap<UUID, LeaderMark>()

    /**
     * Fold [mark] into the leadership index. Adopts iff [mark] is strictly
     * greater than the currently-folded mark for [LeaderMark.logicalId]
     * under [ORDER] (or none is folded yet); returns the verdict. The
     * compare-and-adopt runs inside [ConcurrentHashMap.compute] so the fold
     * is atomic per logical id (f7h.1-D5) — concurrency of the *role
     * application* this verdict may trigger is out of scope here (F7
     * dispute).
     *
     * A duplicate — a mark equal under [ORDER], including refolding the
     * identical mark — is not strictly greater and is therefore rejected
     * (`false`), same as a regression.
     */
    fun markLeader(mark: LeaderMark): Boolean {
        var adopted = false
        leaderMarks.compute(mark.logicalId) { _, current ->
            if (current == null || ORDER.compare(mark, current) > 0) {
                adopted = true
                mark
            } else {
                current
            }
        }
        return adopted
    }

    /**
     * The folded maximum [LeaderMark] for [logicalId], or `null` before any
     * fold. A plain map read — no I/O, no scheduler work (spec 42
     * [MEM1-03]).
     */
    fun leaderOf(logicalId: UUID): LeaderMark? = leaderMarks[logicalId]

    /**
     * Every folded [LeaderMark], one per logical id — F2's catch-up read for
     * a newly-joined peer. A snapshot: unaffected by a fold that happens
     * after this call returns.
     */
    fun leaderMarks(): Collection<LeaderMark> = leaderMarks.values.toList()

    companion object {
        /**
         * The total order [markLeader] adopts under (f7h.1-D2, 93 I-25
         * §4.1): compare `epoch` first, then `leaderRef.instanceId` as the
         * tiebreak — `instanceId` is already "minted collision-free without
         * coordination" ([CellRef]), so no new field is needed on
         * [LeaderMark] to make the order total.
         */
        private val ORDER = compareBy<LeaderMark>({ it.epoch }, { it.leaderRef.instanceId })
    }
}
