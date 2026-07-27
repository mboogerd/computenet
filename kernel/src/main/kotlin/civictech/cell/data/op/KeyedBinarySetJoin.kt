package civictech.cell.data.op

import civictech.cell.Timestamp
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.TagState

/**
 * Shared skeleton for a keyed binary set join (spec 24): the two [TagState]
 * ledgers (one per side), the per-side key index and its byte-identical
 * [index] update, and the copy-pasted emit-or-absorb check shared by
 * [JoinSetCell] and [SemiJoinCell]. Composed, not inherited — see
 * [TaggedSetOperator]'s KDoc for why (each operator must extend its own
 * KSP-generated `XxxCellBase`).
 *
 * [IntersectSetCell] does NOT use the key index: it is an identity join (both
 * sides share element type `E`, matching is direct membership, not a key
 * projection), so it holds its own [TagState] pair directly rather than
 * through this class — forcing it through key-indexed lookup would rewrite a
 * working direct-membership check into an unneeded indirection. It DOES share
 * [JoinLedger] (via [AdvertisedLedger]), the one genuinely shared abstraction
 * across all three operators.
 */
class KeyedBinarySetJoin<A, B, K> {
    internal val leftState = TagState<A>()
    internal val rightState = TagState<B>()

    // derived indexes (rebuilt on restore via [rebuildIndexes]): live rows per key, per side
    val leftIndex = mutableMapOf<K, MutableSet<A>>()
    val rightIndex = mutableMapOf<K, MutableSet<B>>()

    /** The byte-identical per-side key-index update shared by [JoinSetCell] and [SemiJoinCell]. */
    fun <R> index(into: MutableMap<K, MutableSet<R>>, key: K, row: R, live: Boolean) {
        if (live) {
            into.getOrPut(key) { mutableSetOf() } += row
        } else {
            into[key]?.let { it -= row; if (it.isEmpty()) into -= key }
        }
    }

    /** Rebuilds [leftIndex]/[rightIndex] from restored [leftState]/[rightState] (RS-4/M10.1 replay). */
    fun rebuildIndexes(leftKey: (A) -> K, rightKey: (B) -> K) {
        leftIndex.clear()
        rightIndex.clear()
        leftState.elements.forEach { a -> leftIndex.getOrPut(leftKey(a)) { mutableSetOf() } += a }
        rightState.elements.forEach { b -> rightIndex.getOrPut(rightKey(b)) { mutableSetOf() } += b }
    }

    /**
     * The copy-pasted apply/effective/ack shape (CP-A3), byte-identical across
     * [JoinSetCell] and [SemiJoinCell]: [propagate] a non-empty delta built
     * from [adds]/[dels], else [absorbAck] the swallowed wave. Preserves the
     * exact current condition. T05 finding 2: delegates to the shared
     * [emitOrAbsorb] free function.
     */
    fun <Out> emitOrAbsorb(
        adds: Map<Out, Set<Timestamp>>,
        dels: Map<Out, Set<Timestamp>>,
        propagate: (SetDelta<Out>) -> Unit,
        absorbAck: () -> Unit,
    ) = emitOrAbsorb(adds.isEmpty() && dels.isEmpty(), { propagate(SetDelta(adds, dels)) }, absorbAck)
}
