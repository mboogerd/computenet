package civictech.cell.data

import civictech.cell.BoundedStateful
import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.Cursor
import civictech.cell.Propagate
import civictech.cell.StatePage
import civictech.cell.StateRead
import civictech.cell.link.*
import civictech.cell.port.*
import civictech.cell.data.delta.WatermarkDelta
import java.io.Serializable
import java.util.*

/**
 * Replicable delivered-watermark lattice (spec 40/42, E3.2). Each instance owns
 * one replica row (its [slotId]); the cell's converged state is the pointwise-max
 * union of every replica's row plus the grow-only [closed] set, so instances of
 * one logical cell converge by delta gossip over [deltaInlet] exactly like the
 * tagged-set family and [PnCounterCell].
 *
 * There is **no `@Contract`**: the local row is advanced in-process via [advance]
 * and [close] (nothing flows through gen/); only the delta stream and the peer
 * merge inlet are ports. Effective-only re-emission (spec 21): only entries that
 * RAISED a watermark propagate, which terminates mesh echoes.
 *
 * **Four independent lattices** (T11-F self-documentation — SRP audit: this
 * class carries four settlement concerns with no prior enumeration), each
 * grow-only/pointwise-monotone in its own right and each added by a distinct
 * fix as the cross-replica settlement read ([civictech.cell.consistency.ReplicaQuorum])
 * hardened:
 *
 *  1. **`rows`** — the base per-(replica, source) delivered-counter map (spec
 *     40/42 E3.2, the reason this class exists): [advance]/[applyRemote]
 *     pointwise-max merge it, [rows] reads it.
 *  2. **`closed`** (PN-0c) — the grow-only set of cleanly-departed replica
 *     slots: [close] marks one, so a downstream quorum stops waiting on a row
 *     that provably can never advance again.
 *  3. **`suspendEpoch`** (PN-19) — the per-slot recoverable-suspend epoch (odd
 *     = suspended): [suspend]/[resume] toggle it, the DEGRADE-mode analogue of
 *     `closed` for a member that may still come back.
 *  4. **`members`** (FU-2) — the grow-only set of covering-member slots that
 *     have [announceMember]ed their existence, converging membership itself
 *     faster than the point-to-point topology announcements
 *     [civictech.cell.host.InstanceIndex.instancesOf] feeds off.
 *
 * **The rule for a fifth lane**: don't add one here. Each lane above answers a
 * different "what do I know about this replica slot" question, and
 * [civictech.cell.consistency.ReplicaQuorum.frontier] already reads all four
 * independently — a fifth
 * settlement concern (e.g. a future per-slot property unrelated to delivery/
 * departure/suspension/membership) is a signal this cell is doing more than
 * one job and should split into a sibling membership cell instead of growing
 * a fifth `Mutable*` field here (the deferred design; no ticket owns it yet).
 */
class WatermarkCell(override val ref: CellRef = CellRef(UUID.randomUUID())) :
    Cell, BoundedStateful, Replicable<WatermarkDelta> {

    override val outlet = registerPort("outlet", FanOutlet.create<Propagate<WatermarkDelta>>())
    override val deltaInlet = registerPort("deltaInlet", FanInlet.create<Propagate<WatermarkDelta>>())

    // Replay-stable identity (M10.1), mirroring PnCounterCell: the replica slot
    // is DERIVED from the ref, so a recovered instance replaying its journal
    // credits the SAME row the network already saw (pointwise max dedups the
    // replay). A random slot would resurrect a phantom replica row.
    private val slotId: UUID = slotId(ref)

    // full converged state: every replica's row, unioned by pointwise max.
    private val rows: MutableMap<UUID, MutableMap<UUID, Long>> = mutableMapOf()
    private val closed: MutableSet<UUID> = mutableSetOf()
    // PN-19: per-slot suspend epoch (odd = suspended); the resumable analogue of closed.
    private val suspendEpoch: MutableMap<UUID, Long> = mutableMapOf()
    // FU-2: grow-only set of covering member slots that have announced their existence.
    private val members: MutableSet<UUID> = mutableSetOf()

    /** Highest counter this cell has absorbed for [source] from any replica. */
    fun watermark(source: UUID): Long? =
        rows.values.mapNotNull { it[source] }.maxOrNull()

    fun rows(): Map<UUID, Map<UUID, Long>> = rows.mapValues { it.value.toMap() }
    fun closed(): Set<UUID> = closed.toSet()

    /** Slots currently suspended (odd epoch) — the covering-quorum shrink set (PN-19, DEGRADE). */
    fun suspended(): Set<UUID> = suspendEpoch.filterValues { it % 2L != 0L }.keys.toSet()

    /**
     * Covering member slots the companion has learned of (FU-2): every replica that
     * has [announceMember]ed its existence, merged over the mesh. The
     * converged-membership barrier ([civictech.cell.consistency.ReplicaQuorum.frontier])
     * holds a keyed wave while this set names a slot the settling node's
     * [civictech.cell.host.InstanceIndex.instancesOf] view has not yet accounted for.
     */
    fun members(): Set<UUID> = members.toSet()

    /**
     * Announce this replica as a covering member (FU-2): add its [slotId] to the
     * grow-only [members] set and gossip it (effective-only). Because the marker
     * rides the transitively-gossiped companion CRDT, it converges to peers that
     * have not yet learned of this replica through the point-to-point topology
     * announcements — so a settling node can hold on an *unknown* covering member,
     * the asymmetry the R13 creation fence gives a known rowless one.
     */
    fun announceMember() {
        if (!members.add(slotId)) return // effective-only
        outlet.call.propagate(WatermarkDelta(members = setOf(slotId)))
    }

    /**
     * Advance this replica's delivered watermark for [source] to [thru]
     * (monotone, effective-only): a non-raising advance emits nothing.
     */
    fun advance(source: UUID, thru: Long) {
        val row = rows.getOrPut(slotId) { mutableMapOf() }
        if (thru <= (row[source] ?: Long.MIN_VALUE)) return // effective-only (21)
        row[source] = thru
        outlet.call.propagate(WatermarkDelta(rows = mapOf(slotId to mapOf(source to thru))))
    }

    /** Mark this replica cleanly departed: its row stops constraining reads. */
    fun close() {
        if (!closed.add(slotId)) return // effective-only
        outlet.call.propagate(WatermarkDelta(closed = setOf(slotId)))
    }

    /**
     * Mark this replica recoverably suspended (PN-19, spec 34 decision 3): a
     * DEGRADE covering-quorum read drops it while suspended, restoring it on
     * [resume]. Bumps the slot's suspend epoch to the next odd value and gossips
     * it (effective-only — a re-suspend of an already-suspended slot is a no-op).
     */
    fun suspend() {
        val current = suspendEpoch[slotId] ?: 0L
        if (current % 2L != 0L) return // already suspended (effective-only)
        val next = current + 1L
        suspendEpoch[slotId] = next
        outlet.call.propagate(WatermarkDelta(suspended = mapOf(slotId to next)))
    }

    /** Retract a [suspend]: bump the slot's epoch to the next even value (resumed) and gossip it. */
    fun resume() {
        val current = suspendEpoch[slotId] ?: 0L
        if (current % 2L == 0L) return // not suspended (effective-only)
        val next = current + 1L
        suspendEpoch[slotId] = next
        outlet.call.propagate(WatermarkDelta(suspended = mapOf(slotId to next)))
    }

    /** Merge a peer's delta; re-emit exactly the entries that raised a watermark. */
    private fun applyRemote(delta: WatermarkDelta) {
        val raised = mutableMapOf<UUID, MutableMap<UUID, Long>>()
        for ((replica, cols) in delta.rows) {
            val row = rows.getOrPut(replica) { mutableMapOf() }
            for ((source, thru) in cols) {
                if (thru > (row[source] ?: Long.MIN_VALUE)) {
                    row[source] = thru
                    raised.getOrPut(replica) { mutableMapOf() }[source] = thru
                }
            }
        }
        val newlyClosed = delta.closed - closed
        closed += newlyClosed
        val raisedSuspend = mutableMapOf<UUID, Long>()
        for ((slot, epoch) in delta.suspended) {
            if (epoch > (suspendEpoch[slot] ?: Long.MIN_VALUE)) {
                suspendEpoch[slot] = epoch
                raisedSuspend[slot] = epoch
            }
        }
        val newMembers = delta.members - members
        members += newMembers
        if (raised.isEmpty() && newlyClosed.isEmpty() && raisedSuspend.isEmpty() && newMembers.isEmpty()) {
            return // echo terminates here
        }
        outlet.originate { propagate(WatermarkDelta(raised, newlyClosed, raisedSuspend, newMembers)) }
    }

    init {
        deltaInlet.serve(object : Propagate<WatermarkDelta> {
            override fun propagate(value: WatermarkDelta) = applyRemote(value)
        })
        // late-join catch-up (G-22) / replica initial sync + anti-entropy (M7.4):
        // full per-replica state as one delta; pointwise max makes replays harmless.
        outlet.catchUpOnLinked {
            if (rows.isEmpty() && closed.isEmpty() && suspendEpoch.isEmpty() && members.isEmpty()) null
            else WatermarkDelta(rows(), closed(), suspendEpoch.toMap(), members())
        }
    }

    /**
     * Delivery seam (spec 40/42 §Delivered watermarks, E3.3): tap [dataOutlet]
     * so every effective wave the tracked replica makes visible — a local mint
     * OR a peer delta it absorbed and re-emitted (both broadcast through the
     * outlet's `call`/`originate`, which fire taps; targeted `at`-catch-up does
     * not) — advances this watermark for that wave's `(sourceId, counter)`,
     * read straight off the emission's [CurrentContext]. Pointwise-max,
     * effective-only [advance] makes gossip echoes and redeliveries fixpoints;
     * the watermark itself then rides the ordinary replica mesh
     * ([civictech.cell.replication.Replication]) — no new protocol.
     *
     * The advance is detached ([CurrentContext.with] `null`) so the watermark's
     * own gossip emission mints a fresh wave from THIS cell's outlet instead of
     * welding onto the data wave that triggered the tap.
     */
    fun <D> trackDeliveriesOf(dataOutlet: FanOutlet<Propagate<D>>) {
        dataOutlet.tap(Use.fixed(Propagate<D> {
            val ts = CurrentContext.get()?.timestamp ?: return@Propagate
            CurrentContext.with(null) { advance(ts.sourceId, ts.counter) }
        }, PortRef.generate()))
    }

    // ---------------------------------------------------------------------
    // Bounded read (V1C-CELLS). Purely additive, and inert by construction: it
    // enumerates the four backing structures directly and calls none of
    // [advance], [close], [suspend], [resume], [announceMember] or
    // `applyRemote`. It also avoids [rows]/[closed]/[suspended]/[members],
    // whose copies are the whole-state allocation this primitive exists to
    // remove. Nothing is emitted on [outlet] and [CurrentContext] is never
    // entered, so no delivery tap fires and no lattice row moves.
    // ---------------------------------------------------------------------

    /** Which of this cell's four independent lattices an entry came from (V1C-CELLS). */
    enum class WatermarkLane { ROWS, CLOSED, SUSPENDED, MEMBERS }

    /**
     * One `(replica, source, thru)` delivered-counter cell (V1C-CELLS) — **not**
     * a whole replica row. A row is itself unbounded in the number of sources,
     * so making the row the entry would reintroduce exactly the unbounded copy
     * this primitive exists to remove.
     */
    data class WatermarkRowEntry(val replica: UUID, val source: UUID, val thru: Long) : Serializable

    /** One slot in the [WatermarkLane.CLOSED], [WatermarkLane.SUSPENDED] or [WatermarkLane.MEMBERS] lane (V1C-CELLS). */
    data class WatermarkSlotEntry(val lane: WatermarkLane, val slot: UUID, val epoch: Long?) : Serializable

    /** The cursor's key: a lane discriminator plus the key within it (V1C-CELLS). */
    private data class LaneKey(val lane: WatermarkLane, val slot: UUID, val source: UUID?) : Serializable

    /**
     * One page across this cell's four lattices (V1C-CELLS).
     *
     * - **One entry** is one [WatermarkRowEntry] — a `(replica, source, thru)`
     *   triple — for the `rows` lane, and one [WatermarkSlotEntry] for each of
     *   the other three.
     * - **The cursor** names `(lane, slot, source?)`, frozen into a walk order
     *   at walk start ([KeyWalk] of [LaneKey]) and indexed in O(1). A cursor
     *   that did not name its lane could not resume across a lane boundary,
     *   which is the "order across sub-states as well as within them" question
     *   this cell is the concrete instance of.
     * - **The order** is the lanes in [snapshot]'s own order — `rows`,
     *   `closed`, `suspended`, `members` — and, within each, the natural order
     *   of the [UUID] keys (`rows` by `(replica, source)`). Every key here is
     *   `Comparable`, so there is no excuse for an insertion-ordered walk; and
     *   sorting is what makes a [restore]d instance — which refills all four
     *   from `HashMap`/`HashSet` — walk identically to the one that checkpointed.
     * - **`frontier` is null**, and deliberately so. The lattice's contents
     *   *look* like a frontier and are not one: `rows` is a delivered-watermark
     *   lattice over `(replica, source)`, a different key space from this fold's
     *   tag frontier — the per-outlet-epoch lane and the per-origin lane are
     *   explicitly distinct (spec 42, `Replication`'s delivered-watermark
     *   seam). Reporting it as a [civictech.cell.TagFrontier] would be a
     *   category error with a type that happens to fit. Null, not a guess —
     *   with the usual consequence that [StatePage]'s across-page stability
     *   check is neither promised nor verifiable here and the `since`
     *   escalation path is unavailable. `supportsSince`/`supportsScope` stay
     *   false, so either bound is refused rather than silently widened.
     *
     * No entry here can be an exclusive payload — every value is a [UUID] or a
     * [Long] — so nothing is ever elided.
     */
    override fun readBounded(request: StateRead): StatePage {
        @Suppress("UNCHECKED_CAST")
        val walk = (request.cursor?.token as? KeyWalk<LaneKey>) ?: KeyWalk(frozenOrder(), 0)
        val order = walk.order

        val entries = ArrayList<Serializable>(minOf(request.limit, 64))
        var bytes = 0
        var index = walk.next
        val examineThrough = minOf(index + request.limit, order.size)
        while (index < examineThrough) {
            val key = order[index]
            index++
            val entry: Serializable? = when (key.lane) {
                WatermarkLane.ROWS ->
                    rows[key.slot]?.get(key.source)?.let { WatermarkRowEntry(key.slot, key.source!!, it) }

                WatermarkLane.CLOSED ->
                    if (key.slot in closed) WatermarkSlotEntry(WatermarkLane.CLOSED, key.slot, null) else null

                WatermarkLane.SUSPENDED ->
                    suspendEpoch[key.slot]?.let { WatermarkSlotEntry(WatermarkLane.SUSPENDED, key.slot, it) }

                WatermarkLane.MEMBERS ->
                    if (key.slot in members) WatermarkSlotEntry(WatermarkLane.MEMBERS, key.slot, null) else null
            }
            if (entry == null) continue // gone since the walk opened
            entries += entry
            bytes += PageBudget.ENTRY_OVERHEAD_BYTES
            if (PageBudget.exhausted(bytes, request.byteBudget)) break
        }

        val complete = index >= order.size
        return StatePage(
            entries = entries,
            next = if (complete) null else Cursor(KeyWalk(order, index)),
        )
    }

    /**
     * The walk's one O(n log n) pass (V1C-CELLS): flatten the four lanes into a
     * single sorted key sequence once, never per page. Lanes are concatenated in
     * [snapshot]'s order so the walk's shape is the same thing a checkpoint is.
     */
    private fun frozenOrder(): List<LaneKey> {
        val order = ArrayList<LaneKey>(rows.values.sumOf { it.size } + closed.size + suspendEpoch.size + members.size)
        rows.keys.sorted().forEach { replica ->
            rows.getValue(replica).keys.sorted().forEach { source ->
                order += LaneKey(WatermarkLane.ROWS, replica, source)
            }
        }
        closed.sorted().forEach { order += LaneKey(WatermarkLane.CLOSED, it, null) }
        suspendEpoch.keys.sorted().forEach { order += LaneKey(WatermarkLane.SUSPENDED, it, null) }
        members.sorted().forEach { order += LaneKey(WatermarkLane.MEMBERS, it, null) }
        return order
    }

    override fun snapshot(): Serializable =
        HashMap(mapOf<String, Serializable>(
            "rows" to HashMap(rows.mapValues { HashMap(it.value) }),
            "closed" to HashSet(closed),
            "suspended" to HashMap(suspendEpoch),
            "members" to HashSet(members),
        ))

    @Suppress("UNCHECKED_CAST")
    override fun restore(state: Serializable) {
        val map = state as Map<String, Serializable>
        rows.clear()
        closed.clear()
        suspendEpoch.clear()
        members.clear()
        (map.getValue("rows") as Map<UUID, Map<UUID, Long>>).forEach { (r, c) ->
            rows[r] = c.toMutableMap()
        }
        closed += map.getValue("closed") as Set<UUID>
        (map["suspended"] as? Map<UUID, Long>)?.let { suspendEpoch.putAll(it) }
        (map["members"] as? Set<UUID>)?.let { members += it }
    }

    companion object {
        /**
         * The lattice row a [WatermarkCell] with [ref] owns (M10.1 replay-stable):
         * a consumer that knows a companion's ref — e.g.
         * [civictech.cell.replication.Replication]'s per-member read — can name
         * that member's row in [rows] without holding the cell instance.
         */
        fun slotId(ref: CellRef): UUID =
            UUID.nameUUIDFromBytes("watermark-slot:${ref.id}:${ref.instanceId}".toByteArray())
    }
}
