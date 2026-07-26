package civictech.cell.data.view

import civictech.cell.data.delta.MapDelta

/**
 * Effective-only diff-and-emit state for a `MapDelta<K, V>` publisher: owns
 * the published map, recomputes touched keys, and returns only the delta
 * that actually changes downstream state — or null (emit nothing).
 * [changed] customizes the value comparison (e.g. epsilon floats).
 */
class MapDiffPublisher<K, V>(
    private val changed: (V, V) -> Boolean = { a, b -> a != b },
) {
    private val published = LinkedHashMap<K, V>()

    /** Recompute [keys] via [next] (null = key dies), diff against published. */
    fun publish(keys: Iterable<K>, next: (K) -> V?): MapDelta<K, V>? {
        val puts = LinkedHashMap<K, V>()
        val removals = LinkedHashSet<K>()
        keys.forEach { key ->
            val value = next(key)
            val prev = published[key]
            when {
                value == null && prev != null -> {
                    published.remove(key); removals += key
                }

                value != null && (key !in published || changed(prev!!, value)) -> {
                    published[key] = value; puts[key] = value
                }
            }
        }
        return if (puts.isEmpty() && removals.isEmpty()) null else MapDelta(puts, removals)
    }

    /** Diff a fully-recomputed [next] against published: removals are the keys absent from [next]. */
    fun publishAll(next: Map<K, V>): MapDelta<K, V>? =
        publish(next.keys + published.keys) { next[it] }

    /** Published state as a delta-from-empty, or null when empty (catch-up form). */
    fun catchUpDelta(): MapDelta<K, V>? =
        if (published.isEmpty()) null else MapDelta(LinkedHashMap(published), emptySet())

    /** Restore path (Stateful.restore): rebuild published from recomputed state. */
    fun reset(state: Map<K, V>) {
        published.clear(); published.putAll(state)
    }

    fun current(): Map<K, V> = published.toMap()
}
