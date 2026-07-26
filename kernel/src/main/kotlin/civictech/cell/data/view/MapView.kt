package civictech.cell.data.view

import java.io.Serializable
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.op.GroupByCell

/**
 * Consumer-side materialized read model over a [MapDelta] stream: folds
 * last-writer-per-key puts and removals into a queryable map — the canonical
 * fold for anything holding a [MapCell] / [GroupByCell] outlet (an app
 * subscriber, a test, the observation sink). No ports, no host, no wave logic.
 *
 * Convergence caveat is inherited from [MapDelta] (G-23): map deltas carry no
 * causal tags, so this is sound over one FIFO stream but not across concurrent
 * multi-writer conflicts on the same key.
 *
 * Not thread-safe: apply deltas from one thread at a time, like the cells.
 */
class MapView<K, V> {
    private val state = mutableMapOf<K, V>()

    /**
     * Fold one delta in: puts then removals, in arrival order (a single FIFO
     * stream never carries both for one key). Returns whether the map
     * effectively changed — `false` when every put restates the value already
     * held and every removal targets an absent key — so a caller can guard a
     * broadcast on the return.
     */
    fun apply(delta: MapDelta<K, V>): Boolean {
        var changed = false
        delta.puts.forEach { (key, value) ->
            // containsKey guards a nullable V: a genuine null put over an
            // absent key is a change, a restated null is not.
            if (!state.containsKey(key) || state[key] != value) {
                state[key] = value
                changed = true
            }
        }
        delta.removals.forEach { key ->
            if (state.containsKey(key)) {
                state.remove(key)
                changed = true
            }
        }
        return changed
    }

    /** Current entries as an immutable snapshot. */
    fun current(): Map<K, V> = state.toMap()

    operator fun get(key: K): V? = state[key]
    operator fun contains(key: K): Boolean = state.containsKey(key)
    val size: Int get() = state.size

    // snapshot/restore mirror the Stateful shape (matching MapCell), so a view
    // can seed from a StateRequest reply / catch-up delta-from-empty.
    fun snapshot(): Serializable = HashMap(state)

    @Suppress("UNCHECKED_CAST")
    fun restore(state: Serializable) {
        this.state.clear()
        this.state.putAll(state as Map<K, V>)
    }
}
