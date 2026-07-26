package civictech.cell.data

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.Propagate
import civictech.cell.Stateful
import civictech.cell.host.MergeablePayload
import civictech.cell.port.*
import civictech.cell.UuidSerializer
import java.io.Serializable
import java.util.*

/**
 * Delivered-watermark delta (spec 40/42 §Delivered watermarks, E3.2). A row
 * per replica maps each wave **source id** to the highest delivered wave
 * counter that replica has durably absorbed from that source — the same
 * `(sourceId, counter)` clock shape [civictech.cell.Timestamp] uses, one column
 * per source. Merge is **pointwise max per (replica, source)** with a grow-only
 * union of [closed] (a cleanly departed replica's row stops constraining reads).
 *
 * Pointwise max is a join-semilattice: commutative, associative, and
 * **idempotent** — so a re-delivered, already-absorbed delta is a fixpoint and
 * gossip echoes die out exactly as tagged-set and [PnCounterDelta] deltas do.
 * The Naiad-verbatim signed-delta accumulator this replaces (raw addition) is
 * NOT idempotent and double-counts under gossip redelivery — the documented
 * reject; here the watermark can only rise.
 */
@kotlinx.serialization.Serializable
@kotlinx.serialization.SerialName("WatermarkDelta")
data class WatermarkDelta(
    val rows: Map<
        @kotlinx.serialization.Serializable(with = UuidSerializer::class) UUID,
        Map<@kotlinx.serialization.Serializable(with = UuidSerializer::class) UUID, Long>,
        > = emptyMap(),
    val closed: Set<@kotlinx.serialization.Serializable(with = UuidSerializer::class) UUID> = emptySet(),
    /**
     * PN-19 (spec 34 decision 3, 40/42 §Delivered watermarks): the resumable
     * analogue of [closed] — a per-slot **suspend epoch**, merged pointwise by
     * `max`. A slot is currently suspended iff its epoch is odd (each
     * [WatermarkCell.suspend] bumps to the next odd, each [WatermarkCell.resume]
     * to the next even). Only a slot's own owner writes it, so the max-merge is a
     * genuine monotone join (single-writer, alternating) that converges over the
     * gossip mesh exactly as [rows]/[closed] do — the covering-quorum read
     * ([civictech.cell.replication.Replication.replicaFrontier] `degrade`) drops
     * an odd-epoch member from the quorum and restores it when the epoch turns
     * even. `closed` is the degenerate terminal case (an epoch that never turns
     * even again). Defaulted empty ⇒ pre-PN-19 deltas round-trip unchanged.
     */
    val suspended: Map<
        @kotlinx.serialization.Serializable(with = UuidSerializer::class) UUID,
        Long,
        > = emptyMap(),
    /**
     * FU-2 (spec 22 §Converged-membership barrier): a **grow-only set of covering
     * member slots** — the [WatermarkCell.slotId] each replica announces on join
     * ([WatermarkCell.announceMember]). It is the coverage-completeness half of the
     * delivered-watermark companion: a member's *existence* rides the same
     * transitively-gossiped, idempotent CRDT as its delivered frontier, so it
     * converges more completely than the point-to-point topology announcements that
     * feed [civictech.cell.host.LocationRegistry.instancesOf] (which mirror only
     * direct peers). A settling node whose companion lists a member slot its own
     * `instancesOf` view has not accounted for holds every keyed wave
     * ([civictech.cell.replication.Replication.replicaFrontier] `membershipBarrier`)
     * — the same conservative asymmetry the R13 creation fence gives a *known*
     * rowless member, extended to an *unknown* one. Union-merged (grow-only), so it
     * gossips over the mesh with no second protocol; defaulted empty ⇒ pre-FU-2
     * deltas round-trip unchanged.
     */
    val members: Set<@kotlinx.serialization.Serializable(with = UuidSerializer::class) UUID> = emptySet(),
) : Serializable, MergeablePayload {

    fun merge(other: WatermarkDelta): WatermarkDelta =
        WatermarkDelta(
            mergeRows(rows, other.rows),
            closed + other.closed,
            mergeSuspend(suspended, other.suspended),
            members + other.members,
        )

    override fun mergeWith(other: MergeablePayload): MergeablePayload = merge(other as WatermarkDelta)

    /**
     * Lattice order: `this ⊒ other` iff every counter in `other` is met or
     * exceeded here, `other`'s closed replicas are all closed here, every suspend
     * epoch here is at least `other`'s, and `other`'s announced members are all
     * announced here. Used by the monotonicity law and to reason about echo
     * termination.
     */
    fun dominates(other: WatermarkDelta): Boolean {
        if (!closed.containsAll(other.closed)) return false
        if (!members.containsAll(other.members)) return false
        if (other.suspended.any { (slot, epoch) -> (suspended[slot] ?: Long.MIN_VALUE) < epoch }) return false
        return other.rows.all { (replica, cols) ->
            val mine = rows[replica] ?: emptyMap()
            cols.all { (source, thru) -> (mine[source] ?: Long.MIN_VALUE) >= thru }
        }
    }

    companion object {
        private fun mergeSuspend(a: Map<UUID, Long>, b: Map<UUID, Long>): Map<UUID, Long> =
            (a.keys + b.keys).associateWith { maxOf(a[it] ?: Long.MIN_VALUE, b[it] ?: Long.MIN_VALUE) }

        private fun mergeRows(
            a: Map<UUID, Map<UUID, Long>>,
            b: Map<UUID, Map<UUID, Long>>,
        ): Map<UUID, Map<UUID, Long>> =
            (a.keys + b.keys).associateWith { replica ->
                val ca = a[replica] ?: emptyMap()
                val cb = b[replica] ?: emptyMap()
                (ca.keys + cb.keys).associateWith { source ->
                    maxOf(ca[source] ?: Long.MIN_VALUE, cb[source] ?: Long.MIN_VALUE)
                }
            }
    }
}

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
 */
class WatermarkCell(override val ref: CellRef = CellRef(UUID.randomUUID())) :
    Cell, Stateful, Replicable<WatermarkDelta> {

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
     * converged-membership barrier ([civictech.cell.replication.Replication.replicaFrontier])
     * holds a keyed wave while this set names a slot the settling node's
     * [civictech.cell.host.LocationRegistry.instancesOf] view has not yet accounted for.
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
