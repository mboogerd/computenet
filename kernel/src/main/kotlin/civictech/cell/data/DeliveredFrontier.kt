package civictech.cell.data

import java.util.TreeSet
import java.util.UUID

/**
 * A replicable cell that reports its **per-origin delivered frontier** (spec
 * 40/42 §Delivered watermarks, E3.3(a)). As the cell mints or absorbs deltas it
 * notifies listeners of each origin source's advanced delivered position — the
 * position of the ORIGIN wave, read where the incoming delta's origin tags are
 * still visible (inside `applyRemote`), NOT the replica's own re-emission epoch.
 *
 * This is the distinction CP-B2's outlet-tap tracking could not make: a replica
 * re-originates a peer's delta under its own outlet source, discarding the
 * origin before the tap runs, so the tap watermark answers "how many waves has
 * this replica re-emitted", never "which origin waves has the replica set
 * delivered". E3.4's cross-track settlement read needs the latter, so the seam
 * moves into the fold — where the origin tags survive.
 */
interface DeliveryTracking {
    /**
     * Register [listener] to receive `(originSource, deliveredThru)` each time
     * this cell's contiguous delivered prefix for an origin source advances.
     */
    fun onDeliver(listener: (source: UUID, thru: Long) -> Unit)
}

/**
 * Per-origin **max-contiguous delivered prefix** with an out-of-order holdback
 * (spec 40/42 §Delivered watermarks, E3.3(a)). A source's tags are unit-counter
 * dots (`Timestamp(source, 1), (source, 2), …`); over a multi-path gossip mesh
 * they can arrive out of order, so "delivered thru `t`" must mean *every* counter
 * `1..t` has arrived — a plain max would falsely claim a gap was delivered.
 *
 * [deliver] admits a counter, fills the prefix as far as the holdback allows,
 * and returns the new contiguous `thru` only when it advanced — so the watermark
 * it feeds rises exactly once per genuinely-completed prefix, echoes and
 * redeliveries being fixpoints.
 */
class DeliveredFrontier {
    private val prefix = HashMap<UUID, Long>()
    private val holdback = HashMap<UUID, TreeSet<Long>>()

    /**
     * Record delivery of counter [counter] from [source]. Returns the source's
     * new contiguous delivered `thru` if the prefix advanced, or null if
     * [counter] was already covered or merely fills a hole above the prefix.
     */
    fun deliver(source: UUID, counter: Long): Long? {
        val current = prefix[source] ?: 0L
        if (counter <= current) return null // already covered
        val pending = holdback.getOrPut(source) { TreeSet() }
        pending.add(counter)
        var thru = current
        while (pending.remove(thru + 1)) thru++
        if (thru == current) return null // out-of-order: a hole below [counter] remains
        prefix[source] = thru
        return thru
    }
}
