package civictech.cell.data

import civictech.cell.BoundedStateful
import civictech.cell.CellRef
import civictech.cell.Cursor
import civictech.cell.ExclusiveEntry
import civictech.cell.Propagate
import civictech.cell.StatePage
import civictech.cell.StateRead
import civictech.cell.link.*
import civictech.cell.port.*
import civictech.cell.data.delta.MapDelta
import civictech.gen.wire.CellBase
import civictech.gen.wire.Contract
import java.io.Serializable
import java.util.*

@Contract
interface MapOps<K, V> {
    fun put(key: K, value: V)
    fun remove(key: K)
}

@CellBase
interface MapApi<K, V> {
    val inlet: Use<MapOps<K, V>>
    val outlet: Subscribe<Propagate<MapDelta<K, V>>>
}

class MapCell<K, V>(ref: CellRef = CellRef(UUID.randomUUID())) : MapCellBase<K, V>(ref), BoundedStateful {
    private val state = mutableMapOf<K, V>()

    // constructed inline: the factory runs during base-class init, before this
    // class's own fields initialize — the object only *captures* `this`; its
    // methods read subclass state later, at message time.
    override fun inletHandler(): MapOps<K, V> = object : MapOps<K, V> {
        override fun put(key: K, value: V) {
            state[key] = value
            outlet.call.propagate(MapDelta(mapOf(key to value), emptySet()))
        }

        override fun remove(key: K) {
            state.remove(key)
            outlet.call.propagate(MapDelta(emptyMap(), setOf(key)))
        }
    }

    init {
        // late-join catch-up (G-22): current entries as a delta-from-empty
        outlet.catchUpOnLinked { if (state.isEmpty()) null else MapDelta(state.toMap(), emptySet()) }
    }

    override fun snapshot(): Serializable = HashMap(state)

    @Suppress("UNCHECKED_CAST")
    override fun restore(state: Serializable) {
        this.state.clear()
        this.state.putAll(state as Map<K, V>)
    }

    // ---------------------------------------------------------------------
    // Bounded read (V1C-CELLS). Purely additive: nothing above this line
    // changed, and `snapshot()`/`restore()` behave exactly as they did —
    // drain, migration, promotion state transfer and durability checkpoints
    // all depend on that seam being untouched.
    // ---------------------------------------------------------------------

    /** One `(key, value)` pair — a whole entry, never split across pages (V1C-CELLS). */
    data class MapStateEntry<K, V>(val key: K, val value: V) : Serializable

    /**
     * Interest is defined over this cell's key domain, so it can be applied
     * exactly (V1C-CELLS): a `MapCell`'s outlet emits a `MapDelta<K, V>` keyed
     * by `K`, so an [Interest] a consumer of that stream holds is an interest
     * over `K` — `scope.admits(key)` asks the question the caller meant, not a
     * neighbouring one.
     */
    override val supportsScope: Boolean get() = true

    // supportsSince stays false (the safe default): this cell mints no tags, so
    // ManagedHost.readState refuses a non-null `since` rather than letting this
    // cell answer full state as though the delta bound had been applied.

    /**
     * One page of this map's state (V1C-CELLS).
     *
     * - **One entry** is one [MapStateEntry] — a `(key, value)` pair.
     * - **The cursor** names a position in a frozen list of *keys*
     *   ([KeyWalk]); it survives any mutation of the live map, and resuming is
     *   O(1) rather than a rescan.
     * - **The order** is [EntryOrder]'s deterministic total order over `K`,
     *   imposed rather than inherited. `state` is a `LinkedHashMap`, so its own
     *   order is insertion order — which a `remove` then `put` of the same key
     *   moves to the tail, and which [restore] discards entirely when it refills
     *   from the `HashMap` [snapshot] produced. An insertion-ordered walk could
     *   therefore return a key twice, and two instances holding identical state
     *   would enumerate differently.
     * - **`frontier` is null, and that costs the caller something.** This cell
     *   mints no tags: a [civictech.cell.TagFrontier] is "valid only for
     *   per-source-monotone tag families" and there is no such clock here. So
     *   [StatePage]'s across-page stability check — equal endpoint stamps ⇒ the
     *   union is a snapshot — has nothing to compare and is **neither promised
     *   nor verifiable** for a `MapCell`, and the `since`-based escalation path
     *   that would repair a smeared walk is unavailable for the same reason. A
     *   walk under concurrent writes is a smear and the caller cannot tell.
     *
     * An entry whose key or value is an `Owned`/`Leased` payload is never
     * copied into a page: it becomes an [ExclusiveEntry] descriptor and is
     * counted in [StatePage.exclusivesElided].
     */
    @Suppress("UNCHECKED_CAST")
    override fun readBounded(request: StateRead): StatePage {
        val scope = request.scope
        @Suppress("UNCHECKED_CAST")
        val walk = (request.cursor?.token as? KeyWalk<K>) ?: openWalk(scope)
        val order = walk.order

        val entries = ArrayList<Serializable>(minOf(request.limit, 64))
        var elided = 0
        var bytes = 0
        var index = walk.next
        val examineThrough = minOf(index + request.limit, order.size)
        while (index < examineThrough) {
            val key = order[index]
            index++
            if (!state.containsKey(key)) continue // removed since the walk opened
            val value = state[key]
            when {
                ExclusiveEntry.isExclusive(key) -> {
                    entries += ExclusiveEntry.of(key = null, exclusive = key as Any)
                    elided++
                }

                ExclusiveEntry.isExclusive(value) -> {
                    entries += ExclusiveEntry.of(key = key as? Serializable, exclusive = value as Any)
                    elided++
                }

                else -> entries += MapStateEntry(key, value as V)
            }
            bytes += PageBudget.ENTRY_OVERHEAD_BYTES
            if (PageBudget.exhausted(bytes, request.byteBudget)) break
        }

        val complete = index >= order.size
        return StatePage(
            entries = entries,
            next = if (complete) null else Cursor(KeyWalk(order, index)),
            exclusivesElided = elided,
        )
    }

    /** The walk's one O(n log n) pass (V1C-CELLS): impose the key order once, never per page. */
    private fun openWalk(scope: Interest?): KeyWalk<K> {
        val admit: (K) -> Boolean =
            if (scope == null || scope is Interest.Total) { _ -> true } else { k -> scope.admits(k) }
        return KeyWalk(EntryOrder.freeze(state.keys, admit), 0)
    }

    companion object {
        fun <K, V> create(): MapApi<K, V> = MapCell()
    }
}
