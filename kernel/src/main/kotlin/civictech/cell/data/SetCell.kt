package civictech.cell.data

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Stateful
import civictech.cell.Timestamp
import civictech.cell.port.*
import civictech.gen.wire.Contract
import java.io.Serializable
import java.util.*

@Contract
interface SetOps<E> {
    fun add(element: E)
    fun remove(element: E)
}

/**
 * Observed-remove set delta (G-23): every add carries a unique tag; a remove
 * carries exactly the tags it observed. Merging is tag-set union — commutative,
 * associative, idempotent — so membership converges regardless of arrival
 * order. An element is present iff it has an add-tag not covered by a del.
 * Add-wins falls out: a concurrent add's tag is never observed by the remove.
 */
data class SetDelta<E>(
    val adds: Map<E, Set<Timestamp>> = emptyMap(),
    val dels: Map<E, Set<Timestamp>> = emptyMap(),
) : Serializable {
    fun merge(other: SetDelta<E>): SetDelta<E> =
        SetDelta(mergeTags(adds, other.adds), mergeTags(dels, other.dels))

    companion object {
        private fun <E> mergeTags(
            a: Map<E, Set<Timestamp>>,
            b: Map<E, Set<Timestamp>>,
        ): Map<E, Set<Timestamp>> =
            (a.keys + b.keys).associateWith { (a[it] ?: emptySet()) + (b[it] ?: emptySet()) }
    }
}

interface SetApi<E> {
    val inlet: Use<SetOps<E>>
    val outlet: Subscribe<Propagate<SetDelta<E>>>
}

class SetCell<E>(override val ref: CellRef = CellRef(UUID.randomUUID())) : SetApi<E>, Cell, Stateful {
    override val inlet = registerPort("inlet", FanInlet.create<SetOps<E>>())
    override val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<E>>>())

    private val state = mutableMapOf<E, MutableSet<Timestamp>>()

    // Tags are minted locally, not taken from the wave's MessageContext:
    // observed-remove correctness needs a tag unique per add *instance*, and a
    // wave timestamp repeats across every cell the wave touches (22).
    private val tagSource: UUID = UUID.randomUUID()
    private var tagCounter = 0L

    private val inletApi = object : SetOps<E> {
        override fun add(element: E) {
            val tag = Timestamp(tagSource, ++tagCounter)
            state.getOrPut(element) { mutableSetOf() } += tag
            outlet.call.propagate(SetDelta(adds = mapOf(element to setOf(tag))))
        }

        override fun remove(element: E) {
            // effective-only (21): removing an unobserved element is a no-op
            val observed = state.remove(element) ?: return
            outlet.call.propagate(SetDelta(dels = mapOf(element to observed.toSet())))
        }
    }

    init {
        inlet.serve(inletApi)
        // late-join catch-up (G-22): state-as-delta-from-empty to just the new
        // subscriber; tagged deltas make replay after catch-up idempotent
        outlet.linking.onLinked = { link ->
            if (state.isNotEmpty()) {
                outlet.at(link.to).propagate(SetDelta(adds = state.mapValues { it.value.toSet() }))
            }
        }
    }

    // snapshot/restore (G-25 seam): elements must be Serializable
    override fun snapshot(): Serializable =
        HashMap(state.mapValues { HashSet(it.value) })

    @Suppress("UNCHECKED_CAST")
    override fun restore(state: Serializable) {
        this.state.clear()
        (state as Map<E, Set<Timestamp>>).forEach { (e, tags) ->
            this.state[e] = tags.toMutableSet()
        }
    }

    companion object {
        fun <E> create(): SetApi<E> = SetCell()
    }
}
