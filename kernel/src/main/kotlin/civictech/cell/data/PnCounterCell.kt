package civictech.cell.data

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.Stateful
import civictech.cell.link.*
import civictech.cell.port.*
import civictech.cell.data.delta.DeliveryTracking
import civictech.cell.data.delta.PnCounterDelta
import civictech.gen.wire.Contract
import java.io.Serializable
import java.util.*

@Contract
interface PnCounterOps {
    fun increment(amount: Long)
    fun decrement(amount: Long)
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

    /**
     * Guards **every** access to [incs], [decs] and [deliveryListeners] — the
     * read accessors as much as the writers. The counter-shaped twin of
     * `SetCell.stateLock` (computenet-bdth) and `OrMapCell.stateLock`
     * (computenet-yk5r), taken for the same reason and with the same discipline
     * (computenet-ndf6).
     *
     * The cell's writer runs on whichever thread delivers to `inlet` or
     * [deltaInlet], while [total] and [snapshot] are *host*-facing reads a caller
     * makes from its own thread. Unguarded, `incs.values.sum()` in [total] and
     * `HashMap(incs)` in [snapshot] iterate the shared maps and escape a
     * [java.util.ConcurrentModificationException] into the caller — the identical
     * escape observed on CI out of `OrMapCell.membership`. **The writer that
     * reaches it is the remote one**: a local `increment` only rewrites this
     * instance's own slot, which is not a structural modification, while
     * [applyRemote] adds a slot per peer source it has never seen. No
     * `PnCounterCell` caller is known to read from a host thread concurrently
     * with a gossiping peer today, so this is latent rather than observed; it is
     * latent in exactly the way `OrMapCell`'s was until CI found it.
     *
     * **The monitor is never held across an outbound call**, and this cell has
     * two kinds, exactly as `SetCell` does:
     *
     * - `increment`/`decrement` fold under it and propagate after;
     *   [applyRemote] folds under it and originates after.
     * - **Delivery listeners are foreign code and fire outside it.** A
     *   registered listener is `WatermarkCell`'s `companion.advance(…)`
     *   (`Replication.kt`) — a *cell call* that itself does
     *   `outlet.call.propagate`, not a callback into pure state. So the fold
     *   ([foldDelivered]) happens under the monitor and the notification
     *   ([notifyDelivered]) strictly after it is released. Holding the monitor
     *   across that call is exactly how a cross-cell lock cycle would form here.
     *
     * **What it costs.** Reads serialize against the single writer: a host
     * polling [total] over many source slots delays the next write by that scan.
     * Nothing downstream is blocked, per the paragraph above.
     *
     * **Why not the cheaper options.** Copying on read without a guard does not
     * help — the copy is itself an iteration and throws the same CME. A
     * concurrent map would still let [total] tear an `incs` sum against a `decs`
     * sum, and would leave [applyRemote]'s "which entries raised a total" test
     * and the absorption it authorizes as two separate steps.
     */
    private val stateLock = Any()

    fun total(): Long = synchronized(stateLock) { incs.values.sum() - decs.values.sum() }

    // Per-origin delivered frontier (spec 40/42 §Delivered watermarks, E3.3(a)).
    // A PN-counter delta already carries each source's FULL cumulative total, so
    // its "delivered thru" is the cumulative itself (trivially contiguous — no
    // holdback needed) and the natural monotone progress axis is the increment
    // stream: listeners advance on each raised per-source increment cumulative.
    private val deliveryListeners = mutableListOf<(UUID, Long) -> Unit>()

    override fun onDeliver(listener: (source: UUID, thru: Long) -> Unit) = synchronized(stateLock) {
        deliveryListeners += listener
        Unit
    }

    /**
     * The per-source cumulatives to announce for an absorbed set of increments.
     * A PN-counter delta already carries each source's FULL cumulative total, so
     * there is no frontier to fold into — the absorbed cumulatives *are* the
     * progress. Call under [stateLock] (it reads [deliveryListeners]); hand the
     * result to [notifyDelivered] *after* releasing it.
     */
    private fun foldDelivered(cumulativeIncs: Map<UUID, Long>): Map<UUID, Long> =
        if (deliveryListeners.isEmpty()) emptyMap() else cumulativeIncs

    /**
     * Notify listeners of each raised per-source cumulative. **Never called
     * under [stateLock]**: a listener is another cell's call (see [stateLock]).
     */
    private fun notifyDelivered(advanced: Map<UUID, Long>) {
        if (advanced.isEmpty()) return
        val listeners = synchronized(stateLock) { deliveryListeners.toList() }
        for ((source, cumulative) in advanced) listeners.forEach { it(source, cumulative) }
    }

    private val inletApi = object : PnCounterOps {
        override fun increment(amount: Long) {
            if (amount < 0) return decrement(-amount)
            if (amount == 0L) return // effective-only (21)
            // the fold happens under `stateLock`; the listener notification and
            // the propagation after it, never under (see stateLock's KDoc).
            val (cumulative, advanced) = synchronized(stateLock) {
                val c = (incs[sourceId] ?: 0L) + amount
                incs[sourceId] = c
                c to foldDelivered(mapOf(sourceId to c))
            }
            notifyDelivered(advanced)
            outlet.call.propagate(PnCounterDelta(incs = mapOf(sourceId to cumulative)))
        }

        override fun decrement(amount: Long) {
            if (amount < 0) return increment(-amount)
            if (amount == 0L) return
            val cumulative = synchronized(stateLock) {
                val c = (decs[sourceId] ?: 0L) + amount
                decs[sourceId] = c
                c
            }
            outlet.call.propagate(PnCounterDelta(decs = mapOf(sourceId to cumulative)))
        }
    }

    /** Merge a peer's delta; re-emit exactly the entries that raised a total. */
    private fun applyRemote(delta: PnCounterDelta) {
        // one atomic fold: the novelty computation and its absorption must not
        // straddle another writer, and no outbound call happens under the
        // monitor — neither the listener notification nor the re-emission.
        val (effective, advanced) = synchronized(stateLock) {
            val newIncs = delta.incs.filter { (source, total) -> total > (incs[source] ?: 0L) }
            val newDecs = delta.decs.filter { (source, total) -> total > (decs[source] ?: 0L) }
            if (newIncs.isEmpty() && newDecs.isEmpty()) return // echo terminates here
            incs += newIncs
            decs += newDecs
            PnCounterDelta(newIncs, newDecs) to foldDelivered(newIncs)
        }
        notifyDelivered(advanced)
        outlet.originate { propagate(effective) }
    }

    init {
        inlet.serve(inletApi)
        deltaInlet.serve(object : Propagate<PnCounterDelta> {
            override fun propagate(value: PnCounterDelta) = applyRemote(value)
        })
        // late-join catch-up (G-22) — and replica initial sync / anti-entropy
        // (M7.4): full per-source state as one delta; max-merge makes replays harmless
        outlet.catchUpOnLinked {
            synchronized(stateLock) {
                if (incs.isEmpty() && decs.isEmpty()) null else PnCounterDelta(incs.toMap(), decs.toMap())
            }
        }
    }

    override fun snapshot(): Serializable = synchronized(stateLock) {
        HashMap(mapOf("incs" to HashMap(incs), "decs" to HashMap(decs)))
    }

    @Suppress("UNCHECKED_CAST")
    override fun restore(state: Serializable) = synchronized(stateLock) {
        val maps = state as Map<String, Map<UUID, Long>>
        incs.clear()
        decs.clear()
        incs += maps.getValue("incs")
        decs += maps.getValue("decs")
    }
}
