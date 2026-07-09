package civictech.kernel.data

import civictech.kernel.germ.port.*

interface SetOps<E> {
    fun add(element: E)
    fun remove(element: E)
}

data class SetDelta<E>(
    val adds: Set<E>,
    val dels: Set<E>
) {
    fun mergeAddWins(other: SetDelta<E>): SetDelta<E> {
        val newAdds = adds.union(other.adds)
        val newDels = dels.union(other.dels).minus(newAdds)
        return SetDelta(newAdds, newDels)
    }
    fun mergeDelWins(other: SetDelta<E>): SetDelta<E> {
        val newDels = dels.union(other.dels)
        val newAdds = adds.union(other.adds).minus(newDels)
        return SetDelta(newAdds, newDels)
    }

}

interface SetApi<E> {
    val inlet: Use<SetOps<E>>
    val outlet: Subscribe<Propagate<SetDelta<E>>>
}

class SetCell<E> : SetApi<E> {
    override val inlet = registerPort("inlet", FanInlet.create<SetOps<E>>())
    override val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<E>>>())

    private val state = mutableSetOf<E>()
    private val inletApi = object : SetOps<E> {
        override fun add(element: E) {
            state += element
            outlet.call.propagate(SetDelta(setOf(element), emptySet()))
        }

        override fun remove(element: E) {
            state -= element
            outlet.call.propagate(SetDelta(emptySet(), setOf(element)))
        }
    }

    init {
        inlet.serve(inletApi)
    }

    companion object {
        fun <E> create(): SetApi<E> = SetCell()
    }
}

