package civictech.cell.data

import civictech.cell.BoundedStateful
import civictech.cell.CellRef
import civictech.cell.Cursor
import civictech.cell.ExclusiveEntry
import civictech.cell.Propagate
import civictech.cell.ReadCaveat
import civictech.cell.StatePage
import civictech.cell.StateRead
import civictech.cell.link.*
import civictech.cell.port.*
import civictech.cell.data.delta.ListDelta
import civictech.gen.wire.CellBase
import civictech.gen.wire.Contract
import java.io.Serializable
import java.util.*

@Contract
interface ListOps<E> {
    fun add(element: E)
    fun add(index: Int, element: E)
    fun set(index: Int, element: E)
    fun removeAt(index: Int)
}

@CellBase
interface ListApi<E> {
    val inlet: Use<ListOps<E>>
    val outlet: Subscribe<Propagate<ListDelta<E>>>
}

class ListCell<E>(ref: CellRef = CellRef(UUID.randomUUID())) : ListCellBase<E>(ref), BoundedStateful {
    private val state = mutableListOf<E>()

    // constructed inline: the factory runs during base-class init, before this
    // class's own fields initialize — the object only *captures* `this`; its
    // methods read subclass state later, at message time.
    override fun inletHandler(): ListOps<E> = object : ListOps<E> {
        override fun add(element: E) {
            val index = state.size
            state.add(element)
            outlet.call.propagate(ListDelta(adds = listOf(IndexedValue(index, element))))
        }

        override fun add(index: Int, element: E) {
            state.add(index, element)
            outlet.call.propagate(ListDelta(adds = listOf(IndexedValue(index, element))))
        }

        override fun set(index: Int, element: E) {
            state[index] = element
            outlet.call.propagate(ListDelta(updates = listOf(IndexedValue(index, element))))
        }

        override fun removeAt(index: Int) {
            state.removeAt(index)
            outlet.call.propagate(ListDelta(removals = listOf(index)))
        }
    }

    init {
        // late-join catch-up (G-22): current contents as a delta-from-empty
        outlet.catchUpOnLinked { if (state.isEmpty()) null else ListDelta(adds = state.withIndex().toList()) }
    }

    override fun snapshot(): Serializable = ArrayList(state)

    @Suppress("UNCHECKED_CAST")
    override fun restore(state: Serializable) {
        this.state.clear()
        this.state.addAll(state as List<E>)
    }

    // ---------------------------------------------------------------------
    // Bounded read (V1C-CELLS) — the one family with no key, and therefore the
    // one documented exception to the key-based cursor. Purely additive.
    // ---------------------------------------------------------------------

    /**
     * One element and the [index] it held when the page was produced
     * (V1C-CELLS). The index is a *position at page time*, not an identity: a
     * later insertion or removal before it renumbers it.
     */
    data class ListStateEntry<E>(val index: Int, val element: E) : Serializable

    /**
     * One page of this list (V1C-CELLS).
     *
     * - **One entry** is one [ListStateEntry] — `(index, element)`.
     * - **The cursor is POSITIONAL**, and this is the single documented
     *   exception to the bounded read's key-based cursor rule. A list element
     *   has no identity at all: duplicates are legal, [ListOps.set] replaces by
     *   position and [ListOps.removeAt] shifts every later element. Minting a
     *   per-element identity to repair this would mean new state on the fold
     *   path (P2) and a changed [snapshot] shape, both forbidden — for a
     *   diagnostic capability. Every page therefore declares
     *   [ReadCaveat.POSITIONAL_CURSOR], so a consumer reads the weaker
     *   guarantee off the page rather than out of this comment.
     * - **The guarantee, precisely.** *At a stable frontier the walk returns
     *   each element exactly once in list order; under mid-walk mutation a
     *   removal before the cursor can cause an element to be skipped, and an
     *   insertion before the cursor can cause one to be returned twice.* That
     *   is weaker than the interface's "no entry twice in one walk", which is
     *   what the caveat announces. Entries are still whole and a walk still
     *   terminates.
     * - **The order** is list order — the only order a list has, and one that
     *   *is* preserved by [snapshot]/[restore] (an `ArrayList` round trip),
     *   unlike the hash-rebuilt map families.
     * - **`frontier` is null.** This cell mints no tags, so [StatePage]'s
     *   across-page stability check is neither promised nor verifiable here and
     *   the `since`-based escalation path is unavailable. Combined with the
     *   positional cursor, a walk over a concurrently-mutated `ListCell` is the
     *   weakest read in this family — which is stated rather than hidden.
     *
     * An element that is an `Owned`/`Leased` payload is never copied into a
     * page: it becomes an [ExclusiveEntry] descriptor and is counted in
     * [StatePage.exclusivesElided].
     */
    override fun readBounded(request: StateRead): StatePage {
        // the cursor is a bare position: there is no key to freeze, and
        // freezing the elements themselves would copy the whole state, which is
        // the cost this primitive exists to remove
        val from = ((request.cursor?.token as? Int) ?: 0).coerceAtLeast(0)

        val entries = ArrayList<Serializable>(minOf(request.limit, 64))
        var elided = 0
        var bytes = 0
        var index = from
        val examineThrough = minOf(from + request.limit, state.size)
        while (index < examineThrough) {
            val element = state[index]
            if (ExclusiveEntry.isExclusive(element)) {
                entries += ExclusiveEntry.of(key = java.lang.Integer.valueOf(index), exclusive = element as Any)
                elided++
            } else {
                entries += ListStateEntry(index, element)
            }
            index++
            bytes += PageBudget.ENTRY_OVERHEAD_BYTES
            if (PageBudget.exhausted(bytes, request.byteBudget)) break
        }

        // re-read `size` after the loop: the list may only be walked forward, so
        // a walk ends when the cursor reaches the current end
        val complete = index >= state.size
        return StatePage(
            entries = entries,
            next = if (complete) null else Cursor(java.lang.Integer.valueOf(index)),
            caveats = setOf(ReadCaveat.POSITIONAL_CURSOR),
            exclusivesElided = elided,
        )
    }

    companion object {
        fun <E> create(): ListApi<E> = ListCell()
    }
}
