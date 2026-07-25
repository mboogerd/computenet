package civictech.cell.data

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Stateful
import civictech.cell.port.*
import civictech.cell.wire.UuidSerializer
import civictech.gen.wire.Contract
import java.io.Serializable
import java.util.*
import civictech.cell.host.MergeablePayload

@Contract
interface PnCounterOps {
    fun increment(amount: Long)
    fun decrement(amount: Long)
}

/**
 * PN-counter delta (spec 24/42, session delta 4): per-source **cumulative**
 * increment/decrement totals; merging is pointwise max — commutative,
 * associative, **idempotent** — so replica gossip echoes die out the same way
 * tagged set deltas do. This is what plain `CounterDelta` (raw addition, not
 * idempotent, double-counts on a mesh) cannot offer; `CounterCell` therefore
 * stays single-instance, valid for derived per-peer views.
 */
@kotlinx.serialization.Serializable
@kotlinx.serialization.SerialName("PnCounterDelta")
data class PnCounterDelta(
    val incs: Map<@kotlinx.serialization.Serializable(with = UuidSerializer::class) UUID, Long> = emptyMap(),
    val decs: Map<@kotlinx.serialization.Serializable(with = UuidSerializer::class) UUID, Long> = emptyMap(),
) : Serializable, MergeablePayload {
    fun merge(other: PnCounterDelta): PnCounterDelta =
        PnCounterDelta(mergeMax(incs, other.incs), mergeMax(decs, other.decs))
    override fun mergeWith(other: MergeablePayload): MergeablePayload = merge(other as PnCounterDelta)

    companion object {
        private fun mergeMax(a: Map<UUID, Long>, b: Map<UUID, Long>): Map<UUID, Long> =
            (a.keys + b.keys).associateWith { maxOf(a[it] ?: 0L, b[it] ?: 0L) }
    }
}

/**
 * Replicable counter (spec 24/42): each instance accumulates its own
 * contributions under a private source id; state is the pointwise-max union
 * of every source's cumulative totals, so instances of one logical cell
 * converge by delta gossip over [deltaInlet] exactly like the tagged set
 * family. Effective-only re-emission (21): only entries that RAISED a
 * source's total propagate, which terminates mesh echoes.
 *
 * ponytail: source slots grow monotonically (one per instance ever seen);
 * compaction rides the same future work as set tombstones (G-25).
 */
class PnCounterCell(override val ref: CellRef = CellRef(UUID.randomUUID())) :
    Cell, Stateful, Replicable<PnCounterDelta>, DeliveryTracking {

    val inlet = registerPort("inlet", FanInlet.create<PnCounterOps>())
    override val outlet = registerPort("outlet", FanOutlet.create<Propagate<PnCounterDelta>>())
    override val deltaInlet = registerPort("deltaInlet", FanInlet.create<Propagate<PnCounterDelta>>())

    // Replay-stable identity (M10.1): the slot is DERIVED from the ref, so a
    // recovered instance replaying its journal credits the SAME source the
    // network already saw (pointwise max then dedups the replay) — a random
    // slot would double-count at every peer. Slot uniqueness across instances
    // rides instanceId uniqueness (the replication contract).
    private val sourceId: UUID =
        UUID.nameUUIDFromBytes("pn-source:${ref.id}:${ref.instanceId}".toByteArray())

    private val incs = mutableMapOf<UUID, Long>()
    private val decs = mutableMapOf<UUID, Long>()

    fun total(): Long = incs.values.sum() - decs.values.sum()

    // Per-origin delivered frontier (spec 40/42 §Delivered watermarks, E3.3(a)).
    // A PN-counter delta already carries each source's FULL cumulative total, so
    // its "delivered thru" is the cumulative itself (trivially contiguous — no
    // holdback needed) and the natural monotone progress axis is the increment
    // stream: listeners advance on each raised per-source increment cumulative.
    private val deliveryListeners = mutableListOf<(UUID, Long) -> Unit>()

    override fun onDeliver(listener: (source: UUID, thru: Long) -> Unit) {
        deliveryListeners += listener
    }

    private fun recordDelivered(cumulativeIncs: Map<UUID, Long>) {
        if (deliveryListeners.isEmpty()) return
        for ((source, cumulative) in cumulativeIncs) deliveryListeners.forEach { it(source, cumulative) }
    }

    private val inletApi = object : PnCounterOps {
        override fun increment(amount: Long) {
            if (amount < 0) return decrement(-amount)
            if (amount == 0L) return // effective-only (21)
            val cumulative = (incs[sourceId] ?: 0L) + amount
            incs[sourceId] = cumulative
            recordDelivered(mapOf(sourceId to cumulative))
            outlet.call.propagate(PnCounterDelta(incs = mapOf(sourceId to cumulative)))
        }

        override fun decrement(amount: Long) {
            if (amount < 0) return increment(-amount)
            if (amount == 0L) return
            val cumulative = (decs[sourceId] ?: 0L) + amount
            decs[sourceId] = cumulative
            outlet.call.propagate(PnCounterDelta(decs = mapOf(sourceId to cumulative)))
        }
    }

    /** Merge a peer's delta; re-emit exactly the entries that raised a total. */
    private fun applyRemote(delta: PnCounterDelta) {
        val newIncs = delta.incs.filter { (source, total) -> total > (incs[source] ?: 0L) }
        val newDecs = delta.decs.filter { (source, total) -> total > (decs[source] ?: 0L) }
        if (newIncs.isEmpty() && newDecs.isEmpty()) return // echo terminates here
        incs += newIncs
        decs += newDecs
        recordDelivered(newIncs)
        outlet.originate { propagate(PnCounterDelta(newIncs, newDecs)) }
    }

    init {
        inlet.serve(inletApi)
        deltaInlet.serve(object : Propagate<PnCounterDelta> {
            override fun propagate(value: PnCounterDelta) = applyRemote(value)
        })
        // late-join catch-up (G-22) — and replica initial sync / anti-entropy
        // (M7.4): full per-source state as one delta; max-merge makes replays harmless
        outlet.catchUpOnLinked {
            if (incs.isEmpty() && decs.isEmpty()) null else PnCounterDelta(incs.toMap(), decs.toMap())
        }
    }

    override fun snapshot(): Serializable =
        HashMap(mapOf("incs" to HashMap(incs), "decs" to HashMap(decs)))

    @Suppress("UNCHECKED_CAST")
    override fun restore(state: Serializable) {
        val maps = state as Map<String, Map<UUID, Long>>
        incs.clear()
        decs.clear()
        incs += maps.getValue("incs")
        decs += maps.getValue("decs")
    }
}
