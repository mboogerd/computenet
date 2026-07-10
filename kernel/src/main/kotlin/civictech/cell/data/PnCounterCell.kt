package civictech.cell.data

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Stateful
import civictech.cell.port.*
import civictech.cell.wire.UuidSerializer
import civictech.gen.wire.Contract
import java.io.Serializable
import java.util.*

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
) : Serializable {
    fun merge(other: PnCounterDelta): PnCounterDelta =
        PnCounterDelta(mergeMax(incs, other.incs), mergeMax(decs, other.decs))

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
    Cell, Stateful, Replicable<PnCounterDelta> {

    val inlet = registerPort("inlet", FanInlet.create<PnCounterOps>())
    override val outlet = registerPort("outlet", FanOutlet.create<Propagate<PnCounterDelta>>())
    override val deltaInlet = registerPort("deltaInlet", FanInlet.create<Propagate<PnCounterDelta>>())

    // deliberately NOT part of the snapshot: a restored/copied instance mints a
    // fresh slot, so duplicated instances can never write the same source entry
    private val sourceId: UUID = UUID.randomUUID()

    private val incs = mutableMapOf<UUID, Long>()
    private val decs = mutableMapOf<UUID, Long>()

    fun total(): Long = incs.values.sum() - decs.values.sum()

    private val inletApi = object : PnCounterOps {
        override fun increment(amount: Long) {
            if (amount < 0) return decrement(-amount)
            if (amount == 0L) return // effective-only (21)
            val cumulative = (incs[sourceId] ?: 0L) + amount
            incs[sourceId] = cumulative
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
        outlet.call.propagate(PnCounterDelta(newIncs, newDecs))
    }

    init {
        inlet.serve(inletApi)
        deltaInlet.serve(object : Propagate<PnCounterDelta> {
            override fun propagate(value: PnCounterDelta) = applyRemote(value)
        })
        // late-join catch-up (G-22) — and replica initial sync / anti-entropy
        // (M7.4): full per-source state as one delta; max-merge makes replays harmless
        outlet.linking.onLinked = { link ->
            if (incs.isNotEmpty() || decs.isNotEmpty()) {
                outlet.at(link.to).propagate(PnCounterDelta(incs.toMap(), decs.toMap()))
            }
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
