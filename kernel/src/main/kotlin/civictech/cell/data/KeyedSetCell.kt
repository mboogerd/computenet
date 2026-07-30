package civictech.cell.data

import civictech.cell.BoundedStateful
import civictech.cell.CellRef
import civictech.cell.Cursor
import civictech.cell.ExclusiveEntry
import civictech.cell.Propagate
import civictech.cell.StatePage
import civictech.cell.StateRead
import civictech.cell.TagFrontier
import civictech.cell.Timestamp
import civictech.cell.link.*
import civictech.cell.port.*
import civictech.gen.wire.CellBase
import civictech.gen.wire.Contract
import java.io.Serializable
import java.util.*
import civictech.cell.data.delta.SetDelta

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
 * `GroupByCell`, `FlatMapSetCell`, `FilterCell`, `UnionSetCell`, etc.
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
 * it (distinct-projection / OR-set union — the `FlatMapSetCell` many-to-one
 * case). A re-put retracts only the element's tag under *this* key.
 */
class KeyedSetCell<K, E>(ref: CellRef = CellRef(UUID.randomUUID())) :
    KeyedSetCellBase<K, E>(ref), BoundedStateful {
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

    // ---------------------------------------------------------------------
    // Bounded read (V1C-CELLS). Purely additive: `snapshot()`/`restore()` and
    // every fold path above are untouched.
    // ---------------------------------------------------------------------

    /**
     * One key's current binding — a whole entry, never split across pages
     * (V1C-CELLS). The [tag] is the single add-tag this cell minted for
     * [element] under [key]; an entry is therefore small and fixed-size.
     */
    data class KeyedSetStateEntry<K, E>(val key: K, val element: E, val tag: Timestamp) : Serializable

    /**
     * This cell mints per-source-monotone tags from a derived [tagSource], so a
     * real [TagFrontier] exists and `since` is honoured exactly (V1C-CELLS).
     */
    override val supportsSince: Boolean get() = true

    // supportsScope stays false (the safe default), deliberately. This cell has
    // two domains — it is keyed by K and *emits* a SetDelta over E — and an
    // Interest reaching it from a consumer of its outlet is defined over E, not
    // over K. Applying it to K would answer a neighbouring question with the
    // caller's own predicate, which is exactly the silent widening the refuse-
    // by-default rule exists to prevent. ManagedHost.readState refuses instead.

    /**
     * One page of this cell's key -> `(element, tag)` table (V1C-CELLS).
     *
     * - **One entry** is one [KeyedSetStateEntry] — `(key, element, tag)`.
     * - **The cursor** names a position in a frozen list of keys ([KeyWalk]),
     *   so it survives mutation of `current` and resumes in O(1).
     * - **The order** is [EntryOrder]'s deterministic total order over `K`,
     *   imposed rather than inherited: `current` is a `LinkedHashMap` whose
     *   insertion order a [remove][KeyedSetOps.remove]-then-[put][KeyedSetOps.put]
     *   moves to the tail, and which [restore] discards when it refills from the
     *   `HashMap` [snapshot] wrote.
     * - **`frontier` is real, exact, and O(1)** — unlike the OR-set family's,
     *   which costs an O(n) rescan and is therefore stamped exactly only at a
     *   walk's two ends. Every tag this cell has ever minted comes from the one
     *   derived [tagSource] with a counter of `1..tagCounter`, so
     *   `TagFrontier(tagSource -> tagCounter)` *is* the fold's frontier, on
     *   every page, with no caveat.
     *
     *   **What the resulting stability check does and does not catch.** Every
     *   [put][KeyedSetOps.put] that changes anything mints a tag and raises the
     *   counter, so the check sees it. A [remove][KeyedSetOps.remove] mints
     *   nothing — it drops the key and re-uses the tag it already held for the
     *   retraction delta — so a removal applied mid-walk to a key the walk has
     *   already paged leaves the endpoint stamps equal while the union still
     *   names that key bound. Equal endpoint stamps are consequently
     *   *necessary but not sufficient* here, exactly as [StatePage] documents
     *   for the OR-set family and for the same reason: a [TagFrontier] measures
     *   tag gains and only tag gains.
     *
     * [StatePage.attributes] carries `counter` — the tag-minting counter, which
     * is genuinely state (a restored instance must not re-mint tags the network
     * already saw) but is not a per-entry row. It rides **every** page, so a
     * caller joining a walk mid-way still sees it, and with it the union of a
     * walk's pages is exactly [snapshot]'s content.
     *
     * An element that is an `Owned`/`Leased` payload is never copied into a
     * page: it becomes an [ExclusiveEntry] descriptor keyed by its `K` and is
     * counted in [StatePage.exclusivesElided].
     */
    override fun readBounded(request: StateRead): StatePage {
        @Suppress("UNCHECKED_CAST")
        val walk = (request.cursor?.token as? KeyWalk<K>) ?: KeyWalk(EntryOrder.freeze(current.keys) { true }, 0)
        val order = walk.order
        val since = request.since?.perSource?.get(tagSource) ?: -1L

        val entries = ArrayList<Serializable>(minOf(request.limit, 64))
        var elided = 0
        var bytes = 0
        var index = walk.next
        val examineThrough = minOf(index + request.limit, order.size)
        while (index < examineThrough) {
            val key = order[index]
            index++
            val entry = current[key] ?: continue // removed since the walk opened
            if (entry.tag.counter <= since) continue // nothing beyond `since` for this key
            if (ExclusiveEntry.isExclusive(entry.element)) {
                entries += ExclusiveEntry.of(key = key as? Serializable, exclusive = entry.element as Any)
                elided++
            } else {
                entries += KeyedSetStateEntry(key, entry.element, entry.tag)
            }
            bytes += PageBudget.ENTRY_OVERHEAD_BYTES + PageBudget.TAG_BYTES
            if (PageBudget.exhausted(bytes, request.byteBudget)) break
        }

        val complete = index >= order.size
        return StatePage(
            entries = entries,
            next = if (complete) null else Cursor(KeyWalk(order, index)),
            frontier = currentFrontier(),
            exclusivesElided = elided,
            attributes = mapOf("counter" to java.lang.Long.valueOf(tagCounter)),
        )
    }

    /**
     * The fold's tag frontier (V1C-CELLS), in O(1): one source, and its highest
     * minted counter is [tagCounter] by construction. Empty — not
     * `tagSource -> 0` — before the first mint, so "no tag observed" is not
     * reported as "counter 0 observed".
     */
    private fun currentFrontier(): TagFrontier =
        if (tagCounter == 0L) TagFrontier(emptyMap()) else TagFrontier(mapOf(tagSource to tagCounter))

    companion object {
        fun <K, E> create(): KeyedSetApi<K, E> = KeyedSetCell()
    }
}
