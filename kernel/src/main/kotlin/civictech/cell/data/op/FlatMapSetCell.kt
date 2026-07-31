package civictech.cell.data.op

import civictech.cell.BoundedStateful
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.StatePage
import civictech.cell.StateRead
import civictech.cell.Stateful
import civictech.cell.Timestamp
import civictech.cell.port.Serve
import civictech.cell.port.Subscribe
import civictech.cell.link.catchUpOnLinked
import civictech.gen.wire.CellBase
import java.io.Serializable
import java.util.*
import civictech.cell.control.absorbAck
import civictech.cell.data.delta.SetDelta

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
    // BoundedStateful extends Stateful (V1C-KERNEL/V1C-OPS): the paged read is
    // added beside the drain/migration/promotion/durability seam, untouched.
) : FlatMapSetCellBase<A, B>(ref), Stateful, BoundedStateful {
    private val op = TaggedSetOperator<A>()

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
        outlet.catchUpOnLinked { if (op.state.size > 0) SetDelta(adds = remap(op.state.asDelta().adds)) else null }
    }

    override fun onInlet(value: SetDelta<A>) {
        val effective = op.state.apply(value)
        val mapped = SetDelta(remap(effective.adds), remap(effective.dels))
        op.emitOrAbsorb(
            mapped,
            propagate = { outlet.call.propagate(it) },
            absorbAck = { outlet.absorbAck() }, // deduped away — ack the swallowed wave (CP-A3)
        )
    }

    override fun snapshot(): Serializable = op.snapshot()

    override fun restore(state: Serializable) = op.restore(state)

    /**
     * One page of the **input** live tags (V1C-OPS): the single `"live"`
     * sub-state, exactly [snapshot]'s content, via the shared
     * [TaggedSetOperator.page].
     *
     * The mapped output is deliberately absent: this cell keeps no copy of it —
     * `remap` recomputes it from the input state on demand — so it is not in
     * [snapshot] and Decision E keeps it out of the walk. A bounded read of a
     * `FlatMapSetCell` shows the preimages, not `f`'s outputs.
     * `[24-OP-FLATMAP-01]` is untouched: this method only reads and never
     * applies [f].
     */
    override fun readBounded(request: StateRead): StatePage = op.page(request)
}

/** Element-wise map as the one-output flatMap. */
fun <A, B> mapSet(f: (A) -> B): FlatMapSetCell<A, B> = FlatMapSetCell(f = { listOf(f(it)) })
