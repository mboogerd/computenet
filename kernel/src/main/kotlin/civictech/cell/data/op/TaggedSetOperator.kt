package civictech.cell.data.op

import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.TagState
import java.io.Serializable

/**
 * Shared skeleton for a unary tagged-set operator (spec 24 §Established
 * pattern): the live [TagState] ledger, the copy-pasted apply → effective →
 * absorbAck-or-propagate inlet shape ([emitOrAbsorb], CP-A3), and
 * snapshot/restore delegation — the boilerplate common to [FilterCell],
 * [UnionSetCell], and [FlatMapSetCell].
 *
 * Composed, not inherited: each concrete operator must extend its own
 * KSP-generated `XxxCellBase<...>` (`: XxxApi<...>, Cell`, one per `@CellBase`
 * contract) for port registration and inlet dispatch — Kotlin's single class
 * inheritance leaves no slot for a shared `Cell`-extending ancestor across
 * cells whose generated base classes are otherwise unrelated. This class
 * instead holds the ledger and the duplicated emit-or-ack check; each
 * concrete cell wires its own `onInlet`/`catchUpOnLinked` around it and
 * supplies only its own transform — the practical reading of "each operator
 * becomes just its transform expression" under that constraint.
 */
class TaggedSetOperator<E>(
    /** Retain folded del-tags — see [TagState] (D-UNION); only [UnionSetCell] opts in. */
    retainTombstones: Boolean = false,
) {
    internal val state = TagState<E>(retainTombstones)

    fun snapshot(): Serializable = state.snapshot()
    fun restore(saved: Serializable) = state.restore(saved)

    /**
     * The copy-pasted apply/effective/ack shape (CP-A3), byte-identical across
     * [FilterCell], [UnionSetCell], and [FlatMapSetCell]: [propagate] a
     * non-empty [out], else [absorbAck] the swallowed wave. Preserves the
     * exact current condition — nothing added, nothing removed. T05 finding
     * 2: delegates to the shared [emitOrAbsorb] free function.
     */
    fun <X> emitOrAbsorb(out: SetDelta<X>, propagate: (SetDelta<X>) -> Unit, absorbAck: () -> Unit) =
        emitOrAbsorb(out.adds.isEmpty() && out.dels.isEmpty(), { propagate(out) }, absorbAck)
}
