package civictech.cell.data.delta

import civictech.cell.ReBaselineNotice
import civictech.cell.Timestamp
import java.io.Serializable
import java.util.UUID

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

    /**
     * Fenced source ids (spec 20/24 §Tag continuity, 93 I-22 R5c): every
     * source superseded by a `ReBaseline` this fold has processed. A tag
     * stamped by a dead source is rejected from then on — a stale
     * pre-restart delta arriving late via a longer fan-out path.
     * ponytail: unbounded — epoch-hygiene reclamation is research-gated
     * (G-42), implement unbounded-but-correct first.
     */
    private val deadSources = mutableSetOf<UUID>()

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
            // dead-lane fence (93 I-22 R5c): a tag stamped by a superseded source never re-enters
            val admissible = tags.filterNot { it.sourceId in deadSources }.toSet()
            val fresh = admissible - (live[element] ?: emptySet())
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

    /**
     * The convergent-consumer half of a RESTART re-baseline (spec 20/24
     * §Tag continuity, 93 I-22 R5): on `ReBaselineNotice(supersedes,
     * supersede = true)`, (a) drop every live tag from the listed
     * superseded sources this delta does not re-assert, (b) apply [delta] by
     * ordinary tag-union merge, (c) fence the superseded sources as dead
     * lanes. `supersede = false` (pull-merge) retracts nothing — forward
     * idempotent merge only.
     */
    fun applyReBaseline(delta: SetDelta<E>, notice: ReBaselineNotice): SetDelta<E> {
        if (!notice.supersede) return apply(delta)

        val newDels = mutableMapOf<E, Set<Timestamp>>()
        live.keys.toList().forEach { element ->
            val current = live.getValue(element)
            val reasserted = delta.adds[element] ?: emptySet()
            val dropped = current.filter { it.sourceId in notice.supersedes && it !in reasserted }.toSet()
            if (dropped.isNotEmpty()) {
                current -= dropped
                if (current.isEmpty()) live.remove(element)
                newDels[element] = dropped
            }
        }

        // (b) union-merge the reasserted/fresh state — not through apply()'s
        // dead-lane filter, since the reassertion legitimately carries tags
        // from the sources being fenced by this very call (c, below)
        val newAdds = mutableMapOf<E, Set<Timestamp>>()
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
                newDels[element] = (newDels[element] ?: emptySet()) + killed
            }
        }

        // (c) fence the superseded sources — future ordinary deltas from them are rejected
        deadSources += notice.supersedes

        return SetDelta(newAdds, newDels)
    }

    fun snapshot(): Serializable = HashMap(live.mapValues { HashSet(it.value) })

    @Suppress("UNCHECKED_CAST")
    fun restore(state: Serializable) {
        live.clear()
        (state as Map<E, Set<Timestamp>>).forEach { (e, tags) -> live[e] = tags.toMutableSet() }
    }
}
