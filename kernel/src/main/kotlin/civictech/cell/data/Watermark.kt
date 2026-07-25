package civictech.cell.data

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.Stateful
import civictech.cell.host.MergeablePayload
import civictech.cell.port.*
import civictech.cell.wire.UuidSerializer
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
) : Serializable, MergeablePayload {

    fun merge(other: WatermarkDelta): WatermarkDelta =
        WatermarkDelta(mergeRows(rows, other.rows), closed + other.closed)

    override fun mergeWith(other: MergeablePayload): MergeablePayload = merge(other as WatermarkDelta)

    /**
     * Lattice order: `this ⊒ other` iff every counter in `other` is met or
     * exceeded here and `other`'s closed replicas are all closed here. Used by
     * the monotonicity law and to reason about echo termination.
     */
    fun dominates(other: WatermarkDelta): Boolean {
        if (!closed.containsAll(other.closed)) return false
        return other.rows.all { (replica, cols) ->
            val mine = rows[replica] ?: emptyMap()
            cols.all { (source, thru) -> (mine[source] ?: Long.MIN_VALUE) >= thru }
        }
    }

    companion object {
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
    private val slotId: UUID =
        UUID.nameUUIDFromBytes("watermark-slot:${ref.id}:${ref.instanceId}".toByteArray())

    // full converged state: every replica's row, unioned by pointwise max.
    private val rows: MutableMap<UUID, MutableMap<UUID, Long>> = mutableMapOf()
    private val closed: MutableSet<UUID> = mutableSetOf()

    /** Highest counter this cell has absorbed for [source] from any replica. */
    fun watermark(source: UUID): Long? =
        rows.values.mapNotNull { it[source] }.maxOrNull()

    fun rows(): Map<UUID, Map<UUID, Long>> = rows.mapValues { it.value.toMap() }
    fun closed(): Set<UUID> = closed.toSet()

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
        if (raised.isEmpty() && newlyClosed.isEmpty()) return // echo terminates here
        outlet.originate { propagate(WatermarkDelta(raised, newlyClosed)) }
    }

    init {
        deltaInlet.serve(object : Propagate<WatermarkDelta> {
            override fun propagate(value: WatermarkDelta) = applyRemote(value)
        })
        // late-join catch-up (G-22) / replica initial sync + anti-entropy (M7.4):
        // full per-replica state as one delta; pointwise max makes replays harmless.
        outlet.catchUpOnLinked {
            if (rows.isEmpty() && closed.isEmpty()) null
            else WatermarkDelta(rows(), closed())
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
        ))

    @Suppress("UNCHECKED_CAST")
    override fun restore(state: Serializable) {
        val map = state as Map<String, Serializable>
        rows.clear()
        closed.clear()
        (map.getValue("rows") as Map<UUID, Map<UUID, Long>>).forEach { (r, c) ->
            rows[r] = c.toMutableMap()
        }
        closed += map.getValue("closed") as Set<UUID>
    }
}
