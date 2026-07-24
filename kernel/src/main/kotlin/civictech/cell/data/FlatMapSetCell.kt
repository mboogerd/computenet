package civictech.cell.data

import civictech.cell.CellRef
import civictech.cell.Stateful
import civictech.cell.Timestamp
import civictech.cell.port.Serve
import civictech.cell.port.Subscribe
import civictech.cell.port.catchUpOnLinked
import civictech.gen.wire.CellBase
import java.io.Serializable
import java.util.*

@CellBase
interface FlatMapSetApi<A, B> {
    val inlet: Serve<Propagate<SetDelta<A>>>
    val outlet: Subscribe<Propagate<SetDelta<B>>>
}

/**
 * Incremental flatMap over a tagged set stream (M11.1): each input element
 * expands to [f]'s outputs with the input's tags passing through unchanged.
 * Sound because all downstream tag algebra is per-(element, tag): when several
 * inputs map to one output, their tag sets union, so the output stays live
 * until its last live preimage dies — distinct-projection semantics, the
 * many-to-one case included. Tag pass-through respects tag hygiene (21): a
 * membership flip-ON downstream always rides a fresh input add-tag.
 *
 * [f] must be pure — dels re-apply it to translate removals.
 */
class FlatMapSetCell<A, B>(
    ref: CellRef = CellRef(UUID.randomUUID()),
    private val f: (A) -> Iterable<B>,
) : FlatMapSetCellBase<A, B>(ref), Stateful {
    private val state = TagState<A>()

    // fold-with-union: colliding outputs merge tag sets — a mapKeys-style
    // last-wins remap silently drops liveness (the control test's failure class)
    private fun remap(side: Map<A, Set<Timestamp>>): Map<B, Set<Timestamp>> {
        val out = mutableMapOf<B, MutableSet<Timestamp>>()
        side.forEach { (a, tags) -> f(a).forEach { b -> out.getOrPut(b) { mutableSetOf() } += tags } }
        return out
    }

    init {
        // late-join catch-up (G-22): output state is derived, so recompute it
        // from input state rather than keeping a second copy
        outlet.catchUpOnLinked { if (state.size > 0) SetDelta(adds = remap(state.asDelta().adds)) else null }
    }

    override fun onInlet(value: SetDelta<A>) {
        val effective = state.apply(value)
        val mapped = SetDelta(remap(effective.adds), remap(effective.dels))
        if (mapped.adds.isNotEmpty() || mapped.dels.isNotEmpty()) {
            outlet.call.propagate(mapped)
        }
    }

    override fun snapshot(): Serializable = state.snapshot()

    override fun restore(state: Serializable) = this.state.restore(state)
}

/** Element-wise map as the one-output flatMap. */
fun <A, B> mapSet(f: (A) -> B): FlatMapSetCell<A, B> = FlatMapSetCell(f = { listOf(f(it)) })
