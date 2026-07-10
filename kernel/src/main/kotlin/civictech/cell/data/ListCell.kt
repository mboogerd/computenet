package civictech.cell.data

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.port.*
import java.io.Serializable
import java.util.*

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
data class ListDelta<E>(
    val adds: List<IndexedValue<E>> = emptyList(),
    val updates: List<IndexedValue<E>> = emptyList(),
    val removals: List<Int> = emptyList()
) : Serializable

interface ListApi<E> {
    val inlet: Use<ListOps<E>>
    val outlet: Subscribe<Propagate<ListDelta<E>>>
}

class ListCell<E>(override val ref: CellRef = CellRef(UUID.randomUUID())) : ListApi<E>, Cell {
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
    }

    companion object {
        fun <E> create(): ListApi<E> = ListCell()
    }
}
