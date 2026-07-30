package civictech.cell.data.op

import civictech.cell.StatePage
import civictech.cell.StateRead
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
     * One page of this operator's single tagged sub-state (V1C-OPS) — the whole
     * of [civictech.cell.BoundedStateful.readBounded] for [FilterCell],
     * [FlatMapSetCell] and [CountCell], which share one snapshot shape and
     * therefore one implementation.
     *
     * The sub-state is named `"live"` and the cursor degenerates to
     * `(0, element)` over one frozen key sequence — but it is still the shared
     * skeleton's ([OperatorPaging]) frozen sequence, because the obligation to
     * *impose* an enumeration order does not weaken for a single sub-state: this
     * [TagState]'s `live` is a `LinkedHashMap`, so a remove-then-re-add mid-walk
     * would otherwise hand one element to a walk twice.
     *
     * **Not usable by a retaining operator.** With `retainTombstones` the
     * ledger's `snapshot()` is the two-map form, and the tombstone half has no
     * per-element read accessor, so a walk built here would be narrower than the
     * snapshot and break Decision E. [UnionSetCell] — the sole retaining
     * operator — is therefore excluded from V1C-OPS rather than paged partially.
     */
    fun page(request: StateRead): StatePage = pageOver(
        request,
        listOf(tagSubState(LIVE, state)),
        frontier = { state.contributeTo(FrontierBuilder()).build() },
    )

    companion object {
        /** The sub-state name every [TaggedSetOperator]-backed cell pages under (V1C-OPS). */
        const val LIVE = "live"
    }

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
