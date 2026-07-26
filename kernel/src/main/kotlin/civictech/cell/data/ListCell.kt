package civictech.cell.data

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.Stateful
import civictech.cell.port.*
import civictech.cell.IndexedValueSerializer
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

/**
 * Convergence limit (G-23, documented): index-addressed deltas are only
 * meaningful in the emission order of a single FIFO stream — concurrent
 * multi-writer edits do not converge. Stable multi-writer sequences need
 * position identifiers (RGA/LSEQ style), out of scope until replication (42).
 */
@kotlinx.serialization.Serializable
@kotlinx.serialization.SerialName("ListDelta")
data class ListDelta<E>(
    val adds: List<@kotlinx.serialization.Serializable(with = IndexedValueSerializer::class) IndexedValue<E>> = emptyList(),
    val updates: List<@kotlinx.serialization.Serializable(with = IndexedValueSerializer::class) IndexedValue<E>> = emptyList(),
    val removals: List<Int> = emptyList()
) : Serializable

@CellBase
interface ListApi<E> {
    val inlet: Use<ListOps<E>>
    val outlet: Subscribe<Propagate<ListDelta<E>>>
}

class ListCell<E>(ref: CellRef = CellRef(UUID.randomUUID())) : ListCellBase<E>(ref), Stateful {
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

    companion object {
        fun <E> create(): ListApi<E> = ListCell()
    }
}
