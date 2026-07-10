package civictech.cell.data

import civictech.cell.Timestamp
import java.io.Serializable

/**
 * The shared state shape of tag-consuming cells (G-23): live add-tags per
 * element. [apply] folds a delta in and returns only the *new* tag
 * information, so duplicate deliveries across diamond fan-ins dedup.
 *
 * ponytail: no del tombstones — per-link FIFO means a tag's add precedes its
 * del on every stream, so a dropped del of an unseen tag recurs on the stream
 * that carries the add; diamond fan-ins may flicker transiently but converge
 * at idle.
 */
internal class TagState<E> {
    private val live = mutableMapOf<E, MutableSet<Timestamp>>()

    val elements: Set<E> get() = live.keys
    val size: Int get() = live.size
    operator fun contains(element: E): Boolean = element in live
    fun tags(element: E): Set<Timestamp> = live[element]?.toSet() ?: emptySet()

    /** Current state as a delta-from-empty — the catch-up emission (G-22). */
    fun asDelta(): SetDelta<E> = SetDelta(adds = live.mapValues { it.value.toSet() })

    fun apply(delta: SetDelta<E>): SetDelta<E> {
        val newAdds = mutableMapOf<E, Set<Timestamp>>()
        val newDels = mutableMapOf<E, Set<Timestamp>>()

        delta.adds.forEach { (element, tags) ->
            val fresh = tags - (live[element] ?: emptySet())
            if (fresh.isNotEmpty()) {
                live.getOrPut(element) { mutableSetOf() } += fresh
                newAdds[element] = fresh
            }
        }
        delta.dels.forEach { (element, tags) ->
            val had = live[element] ?: return@forEach
            val killed = tags intersect had
            if (killed.isNotEmpty()) {
                had -= killed
                if (had.isEmpty()) live.remove(element)
                newDels[element] = killed
            }
        }
        return SetDelta(newAdds, newDels)
    }

    fun snapshot(): Serializable = HashMap(live.mapValues { HashSet(it.value) })

    @Suppress("UNCHECKED_CAST")
    fun restore(state: Serializable) {
        live.clear()
        (state as Map<E, Set<Timestamp>>).forEach { (e, tags) -> live[e] = tags.toMutableSet() }
    }
}
