package civictech.cell.data

import java.io.Serializable

/**
 * Consumer-side materialized read model over a per-key count stream (the
 * slotfinder `byDay` fold): folds a [MapDelta] of `K -> Long` into queryable
 * counts. A thin specialization of [MapView] with a zero-defaulting [count]
 * accessor; it shares MapView's last-writer-per-key semantics — upstream (a
 * [GroupByCell]-style fold) recomputes each count and re-puts it — so it does
 * not diverge from the map fold. No ports, no host, no wave logic.
 *
 * Not thread-safe: apply deltas from one thread at a time, like the cells.
 */
class CountView<K> {
    private val view = MapView<K, Long>()

    /** Fold one count delta in; returns whether any count effectively changed. */
    fun apply(delta: MapDelta<K, Long>): Boolean = view.apply(delta)

    /** All present counts as an immutable snapshot. */
    fun current(): Map<K, Long> = view.current()

    /** Count for [key], or 0 when it has never been counted / was removed. */
    fun count(key: K): Long = view[key] ?: 0L

    val size: Int get() = view.size

    fun snapshot(): Serializable = view.snapshot()
    fun restore(state: Serializable) = view.restore(state)
}
