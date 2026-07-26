package civictech.cell.proxy

/**
 * ParkQueue — the append-in-order / hold / drain-once primitive (PN-11).
 *
 * One hand-rolled shape, previously reinvented at five sites across three type
 * levels: [civictech.cell.port.FanInlet]'s cold-port tail, the
 * [civictech.cell.membrane.TrafficLightCell] red buffer,
 * [civictech.cell.host.LocationRegistry]'s per-ref location parking,
 * [civictech.cell.host.ManagedHost]'s suspended-cell parking, and the
 * [civictech.cell.data.PartitionedShardSet] repartition flip buffer. In every
 * case something arrives while a gate is shut, accumulates in *arrival order*
 * (the hold), and replays *once, in order* when the gate opens (the drain).
 *
 * The gate itself always lives at the site (a served handler, a green light, a
 * published location, a resumed cell, a closed flip window) — this type owns only
 * the ordered hold and the drain, so the drain-once discipline lives in one place
 * (the control: a drain-twice or hold-leak here diverges the migration/flip pins).
 *
 * It implements [MutableList] by delegation so the existing [Buffering]
 * `InvocationHandler` appends to it unchanged and the location-park's
 * `synchronized(queue)` inspection still works; [park] is the intent-named alias
 * for [MutableList.add]. Prefer [park]/[drain]/[drainWhile] over hand-rolled
 * iterate-and-clear — that hand-rolling is exactly what this unifies.
 */
class ParkQueue<T> private constructor(
    private val items: MutableList<T>,
) : MutableList<T> by items {

    constructor() : this(mutableListOf())

    /** Append in arrival order (the hold). Intent-named alias for [add]. */
    fun park(item: T) {
        items.add(item)
    }

    /**
     * Remove and return everything parked, in park order — once. Draining an
     * empty queue yields an empty list, so the site's `if (isEmpty) return` guard
     * is subsumed. The snapshot is taken before the clear, so a replay that
     * re-enters the site cannot re-observe the tail (drain-once).
     */
    fun drain(): List<T> {
        val tail = items.toList()
        items.clear()
        return tail
    }

    /**
     * Drain in park order while [action] accepts each head, stopping — and
     * retaining the remainder, in order — at the first rejection. The resumable
     * form the location-park uses when a downstream intake can refuse mid-drain
     * (a saturated or closed intake), so the rejected head and its successors stay
     * parked for the next attempt.
     */
    fun drainWhile(action: (T) -> Boolean) {
        while (items.isNotEmpty()) {
            if (!action(items.first())) return
            items.removeAt(0)
        }
    }
}
