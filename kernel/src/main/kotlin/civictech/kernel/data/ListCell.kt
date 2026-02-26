package civictech.kernel.data

import civictech.kernel.germ.port.*

interface ListOps<E> {
    fun add(element: E)
    fun add(index: Int, element: E)
    fun set(index: Int, element: E)
    fun removeAt(index: Int)
}

data class ListDelta<E>(
    val adds: List<IndexedValue<E>> = emptyList(),
    val updates: List<IndexedValue<E>> = emptyList(),
    val removals: List<Int> = emptyList()
)

interface ListApi<E> {
    val inlet: Use<ListOps<E>>
    val outlet: Subscribe<Propagate<ListDelta<E>>>
}

class ListCell<E> : ListApi<E> {
    override val inlet = FanInlet.create<ListOps<E>>()
    override val outlet = FanOutlet.create<Propagate<ListDelta<E>>>()

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
