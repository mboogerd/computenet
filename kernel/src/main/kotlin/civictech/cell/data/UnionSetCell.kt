package civictech.cell.data

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Timestamp
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.Serve
import civictech.cell.port.Subscribe
import civictech.cell.port.registerPort
import java.util.*

interface UnionSetApi<E> {
    val inlet: Serve<Propagate<SetDelta<E>>>
    val outlet: Subscribe<Propagate<SetDelta<E>>>
}

/**
 * Merges tagged delta streams (G-23): tracks live add-tags per element and
 * forwards only new tag information, so duplicate deliveries across a diamond
 * fan-in dedup instead of double-counting. Membership (an element is in the
 * union iff it has a live tag) is derivable by any consumer from the forwarded
 * tag algebra.
 */
class UnionSetCell<E>(override val ref: CellRef = CellRef(UUID.randomUUID())) : UnionSetApi<E>, Cell {
    override val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<E>>>())
    override val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<E>>>())

    private val live = mutableMapOf<E, MutableSet<Timestamp>>()

    init {
        inlet.serve(object : Propagate<SetDelta<E>> {
            override fun propagate(value: SetDelta<E>) {
                // ponytail: no del tombstones — per-link FIFO means a tag's add
                // precedes its del on every stream, so a dropped del of an
                // unseen tag recurs on the stream that carries the add; diamond
                // fan-ins may flicker transiently but converge at idle.
                val newAdds = mutableMapOf<E, Set<Timestamp>>()
                val newDels = mutableMapOf<E, Set<Timestamp>>()

                value.adds.forEach { (element, tags) ->
                    val fresh = tags - (live[element] ?: emptySet())
                    if (fresh.isNotEmpty()) {
                        live.getOrPut(element) { mutableSetOf() } += fresh
                        newAdds[element] = fresh
                    }
                }
                value.dels.forEach { (element, tags) ->
                    val had = live[element] ?: return@forEach
                    val killed = tags intersect had
                    if (killed.isNotEmpty()) {
                        had -= killed
                        if (had.isEmpty()) live.remove(element)
                        newDels[element] = killed
                    }
                }

                if (newAdds.isNotEmpty() || newDels.isNotEmpty()) {
                    outlet.call.propagate(SetDelta(newAdds, newDels))
                }
            }
        })
    }

    companion object {
        fun <E> create(): UnionSetApi<E> = UnionSetCell()
    }
}
