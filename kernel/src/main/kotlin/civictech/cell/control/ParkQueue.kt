package civictech.cell.control

/**
 * ParkQueue — the append-in-order / hold / drain-once primitive (PN-11).
 *
 * One hand-rolled shape, previously reinvented at five sites across three type
 * levels: [civictech.cell.port.FanInlet]'s cold-port tail, the
 * [civictech.cell.membrane.TrafficLightCell] red buffer,
 * [civictech.cell.host.LocationRegistry]'s per-ref location parking,
 * [civictech.cell.host.ManagedHost]'s suspended-cell parking, and the
 * [civictech.cell.partition.PartitionedShardSet] repartition flip buffer. In every
 * case something arrives while a gate is shut, accumulates in *arrival order*
 * (the hold), and replays *once, in order* when the gate opens (the drain).
 *
 * The gate itself always lives at the site (a served handler, a green light, a
 * published location, a resumed cell, a closed flip window) — this type owns only
 * the ordered hold and the drain, so the drain-once discipline lives in one place
 * (the control: a drain-twice or hold-leak here diverges the migration/flip pins).
 *
 * Exposes only the intent-named surface below (T03 — no longer `MutableList`
 * by delegation): a bare `by items` let any caller `clear()`/`remove()`/
 * `removeAt()` a park queue directly — precisely the "silently drop parked
 * exclusives" operation the project bans (`Owned`/`Leased` payloads may sit
 * in a `parked` `Invocation`). [Buffering] takes a `(T) -> Unit` recorder now,
 * so its callers pass `queue::park` instead of the queue itself; every other
 * call site already used [park]/[drain]/[drainWhile]. [snapshot] is the
 * read-only replacement for what used to be free `Iterable`/`List` access
 * (`toList()`, iteration, `size`, `isEmpty()`).
 */
class ParkQueue<T> private constructor(
    private val items: MutableList<T>,
) {

    constructor() : this(mutableListOf())

    /** Append in arrival order (the hold). Intent-named alias for `MutableList.add`. */
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

    /** Current park count, without draining. */
    val size: Int get() = items.size

    /** True iff nothing is currently parked. */
    fun isEmpty(): Boolean = items.isEmpty()

    /** A read-only copy of what's currently parked, in park order — inspection only, never a mutation handle. */
    fun snapshot(): List<T> = items.toList()
}
