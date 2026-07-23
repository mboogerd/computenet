package civictech.cell.data

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Stateful
import civictech.cell.port.*
import civictech.cell.wire.IndexedValueSerializer
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

interface ListApi<E> {
    val inlet: Use<ListOps<E>>
    val outlet: Subscribe<Propagate<ListDelta<E>>>
}

class ListCell<E>(override val ref: CellRef = CellRef(UUID.randomUUID())) : ListApi<E>, Cell, Stateful {
    override val inlet = registerPort("inlet", FanInlet.create<ListOps<E>>())
    override val outlet = registerPort("outlet", FanOutlet.create<Propagate<ListDelta<E>>>())

    private val state = mutableListOf<E>()

    private val inletApi = object : ListOps<E> {
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
        inlet.serve(inletApi)
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
