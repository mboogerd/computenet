package civictech.cell.data

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.Stateful
import civictech.cell.Timestamp
import civictech.cell.link.*
import civictech.cell.port.*
import civictech.gen.wire.CellBase
import civictech.gen.wire.Contract
import java.io.Serializable
import java.util.*
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.op.FilterCell
import civictech.cell.data.op.UnionSetCell
import civictech.cell.data.op.FlatMapSetCell
import civictech.cell.data.op.GroupByCell

/**
 * Keyed upsert input (F-3): the latest element under a [key] replaces the
 * previous one. Unlike [SetOps.add]/[SetOps.remove] the caller need not remember
 * the old element to retract it — the cell owns that memory, so a re-[put]
 * retracts-then-adds by itself.
 */
@Contract
interface KeyedSetOps<K, E> {
    fun put(key: K, element: E)
    fun remove(key: K)
}

@CellBase
interface KeyedSetApi<K, E> {
    val inlet: Use<KeyedSetOps<K, E>>
    val outlet: Subscribe<Propagate<SetDelta<E>>>
}

/**
 * A source cell whose input is keyed upserts and whose output is a [SetDelta]
 * stream — the impedance bridge between [MapCell]'s upsert-keyed writes and
 * [SetCell]'s tagged set algebra (F-3). It owns the "what element was under this
 * key" memory that demos otherwise keep by hand (a shadow index, plus the
 * remove-old-then-add dance), and emits set deltas so it plugs straight into
 * [GroupByCell], [FlatMapSetCell], [FilterCell], [UnionSetCell], etc.
 *
 * Re-put atomicity: a [put] over an existing key ships the previous element's
 * retraction and the new element's add in ONE [SetDelta], so a downstream
 * consumer never momentarily sees two live elements for a key, nor zero. This is
 * exactly OR-set membership hygiene (spec 21): the old element's add-tag dies,
 * the new element's add-tag is fresh, and the cell — the single writer of these
 * element identities — mints tags cleanly.
 *
 * Tags are keyed *per key*, not per element: if two keys hold the same element,
 * the element carries both keys' add-tags and stays live until both keys drop
 * it (distinct-projection / OR-set union — the [FlatMapSetCell] many-to-one
 * case). A re-put retracts only the element's tag under *this* key.
 */
class KeyedSetCell<K, E>(ref: CellRef = CellRef(UUID.randomUUID())) :
    KeyedSetCellBase<K, E>(ref), Stateful {
    /** The element under a key and the single add-tag this cell minted for it. */
    private class Entry<E>(val element: E, val tag: Timestamp)

    // key → current element + its live add-tag. This is the shadow index F-3
    // asks the framework to own.
    private val current = mutableMapOf<K, Entry<E>>()

    // Replay-stable identity (M10.1), same construction as SetCell: the tag
    // source is DERIVED from ref + instanceId, so a recovered instance replaying
    // its journal re-mints the exact tags the network already observed —
    // a random source would resurrect retracted elements.
    private val tagSource: UUID =
        UUID.nameUUIDFromBytes("keyed-set-tags:${ref.id}:${ref.instanceId}".toByteArray())
    private var tagCounter = 0L

    // constructed inline: the factory runs during base-class init, before this
    // class's own fields initialize — the object only *captures* `this`; its
    // methods read subclass state later, at message time.
    override fun inletHandler(): KeyedSetOps<K, E> = object : KeyedSetOps<K, E> {
        override fun put(key: K, element: E) {
            val prev = current[key]
            // effective-only (21): re-putting the identical element is a no-op,
            // no spurious retract/add churn downstream.
            if (prev != null && prev.element == element) return
            val tag = Timestamp(tagSource, ++tagCounter)
            current[key] = Entry(element, tag)
            // retract-then-add atomically: the previous element's tag dies in
            // the SAME delta that carries the new element's fresh add-tag, so a
            // downstream fold never observes two live elements (or none) for key.
            outlet.call.propagate(
                SetDelta(
                    adds = mapOf(element to setOf(tag)),
                    dels = prev?.let { mapOf(it.element to setOf(it.tag)) } ?: emptyMap(),
                )
            )
        }

        override fun remove(key: K) {
            // removing a key the cell never held is a no-op.
            val prev = current.remove(key) ?: return
            outlet.call.propagate(SetDelta(dels = mapOf(prev.element to setOf(prev.tag))))
        }
    }

    init {
        // late-join catch-up (G-22): all current elements as one delta-from-
        // empty. Two keys holding the same element union their add-tags, so the
        // late subscriber's fold agrees with the live one on membership.
        outlet.catchUpOnLinked {
            if (current.isEmpty()) null
            else {
                val adds = mutableMapOf<E, MutableSet<Timestamp>>()
                current.values.forEach { adds.getOrPut(it.element) { mutableSetOf() } += it.tag }
                SetDelta(adds = adds)
            }
        }
    }

    // snapshot/restore (G-25 seam): keys and elements must be Serializable. The
    // tag counter is state too (M10.2) — a checkpoint-restored instance must not
    // re-mint tags it already used, or a post-restore put could collide with a
    // tag the network still remembers.
    override fun snapshot(): Serializable =
        HashMap(
            mapOf(
                "current" to HashMap(current.mapValues { arrayListOf<Serializable>(it.value.element as Serializable, it.value.tag) }),
                "counter" to tagCounter,
            )
        )

    @Suppress("UNCHECKED_CAST")
    override fun restore(state: Serializable) {
        val maps = state as Map<String, Any>
        current.clear()
        (maps.getValue("current") as Map<K, List<Any>>).forEach { (k, entry) ->
            current[k] = Entry(entry[0] as E, entry[1] as Timestamp)
        }
        tagCounter = maps["counter"] as? Long ?: 0L
    }

    companion object {
        fun <K, E> create(): KeyedSetApi<K, E> = KeyedSetCell()
    }
}
