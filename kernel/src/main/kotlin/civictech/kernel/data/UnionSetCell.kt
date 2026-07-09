package civictech.kernel.data

import civictech.kernel.germ.port.FanInlet
import civictech.kernel.germ.port.registerPort
import civictech.kernel.germ.port.FanOutlet
import civictech.kernel.germ.port.Serve
import civictech.kernel.germ.port.Subscribe

interface UnionSetApi<E> {
    val inlet: Serve<Propagate<SetDelta<E>>>
    val outlet: Subscribe<Propagate<SetDelta<E>>>
}

class UnionSetCell<E> : UnionSetApi<E> {
    override val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<E>>>())
    override val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<E>>>())

    private val elementCounts = mutableMapOf<E, Int>()

    init {
        inlet.serve(object : Propagate<SetDelta<E>> {
            override fun propagate(value: SetDelta<E>) {
                val effectiveAdds = mutableSetOf<E>()
                val effectiveDels = mutableSetOf<E>()

                // Process adds first
                value.adds.forEach { element ->
                    elementCounts.compute(element) { _, count ->
                        val current = count ?: 0
                        if (current == 0) effectiveAdds.add(element)
                        current + 1
                    }
                }

                // Process dels
                value.dels.forEach { element ->
                    elementCounts.computeIfPresent(element) { _, count ->
                        if (count == 1) {
                            effectiveDels.add(element)
                            null // remove from map
                        } else {
                            count - 1
                        }
                    }
                }

                // If an element was both added and deleted in the same delta, it might end up in both effective sets
                // or neither, depending on the desired semantics. 
                // However, SetDelta is usually used to represent a transition.
                // If we want to be safe, we should ensure they don't overlap in the outgoing delta.
                
                val finalAdds = effectiveAdds - effectiveDels
                val finalDels = effectiveDels - effectiveAdds

                if (finalAdds.isNotEmpty() || finalDels.isNotEmpty()) {
                    outlet.call.propagate(SetDelta(finalAdds, finalDels))
                }
            }
        })
    }

    companion object {
        fun <E> create(): UnionSetApi<E> = UnionSetCell()
    }
}