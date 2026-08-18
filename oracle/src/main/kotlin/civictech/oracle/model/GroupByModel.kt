package civictech.oracle.model

import java.io.Serializable

/**
 * `GroupByCell(keyFn, aggregator)` and `GroupByCell.global` — grouped aggregation
 * (`[24-OP-GROUPBY-01]`, `[24-OP-GROUPBY-02]`, `[24-AGG-01]`, `[ORA1-MODEL-06]`):
 *
 * ```
 * { keyFn(e) -> aggregate(group) | group = { e in liveInput | keyFn(e) == key }, group != {} }
 * ```
 *
 * The live input set is grouped by [keyFn], and each group's value is computed **from
 * scratch** over that group's live elements by [aggregate]. Nothing is carried between
 * evaluations, which is the whole reason this reference is worth having: the kernel maintains
 * a per-group accumulator and a live-element count, applying `insert`/`retract` on membership
 * flips, and the model recomputes — so the two agree only if the incremental fold really is
 * equivalent to the batch one, which is the property `[24-AGG-01]` asserts and a differential
 * run checks.
 *
 * ## Group death is structural here (BS-6)
 *
 * `[24-OP-GROUPBY-02]`: *"a group's last retraction SHALL remove the group from the `MapDelta`
 * outlet"* — SQL group-death semantics. The kernel gets that from an explicit counter (`if
 * (g.count == 0) groups.remove(k)`), and getting it wrong is a real and tempting bug: leaving
 * the key present with a stale value, or with the aggregator's identity (`0` for a count, `0`
 * for a sum), reads as harmless and is not. In the model the failure is unrepresentable — the
 * result's key set is derived from the live elements, so a key with no live element is never
 * created — and `[ORA1-MODEL-06]` is the requirement that this be so. `GroupByAggregatorTest`
 * pins it for each of the seven aggregate families, because "unrepresentable" is a claim about
 * *this* implementation that only a test keeps true.
 *
 * ## `global`
 *
 * `GroupByCell.global(aggregator)` is fold-to-scalar as *one constant-key group*
 * (`[24-OP-GROUPBY-01]`), and its outlet is still a `MapDelta<String, A>` under the key
 * `"global"` — so [global] is this same model with a constant [ElementKey], producing a
 * one-entry [ModelState.MapState] rather than a [ModelState.ScalarState]. Modelling it as a
 * scalar would not be structurally equal to what a terminal over that outlet reads.
 * An empty input yields an **empty map**, not `{"global" -> identity}`: the constant-key group
 * dies with its last element exactly like any other.
 */
class GroupByModel(
    private val keyFn: ElementKey,
    private val aggregate: AggregateFunction,
) : OperatorModel, Serializable {

    override fun evaluate(inputs: List<ModelState>): ModelState {
        require(inputs.size == 1) { "GroupByModel is unary; got ${inputs.size} inputs" }
        val live = inputs[0].asSet("GroupByModel")

        val groups = LinkedHashMap<Any?, MutableList<Any?>>()
        live.elements.forEach { element -> groups.getOrPut(keyFn.keyOf(element)) { mutableListOf() } += element }

        return ModelState.MapState(groups.mapValues { (_, members) -> aggregate.aggregate(members) })
    }

    override fun toString(): String = "GroupByModel($keyFn, $aggregate)"

    companion object {
        /**
         * The constant key `GroupByCell.global` groups under — the kernel's `keyFn = { "global" }`
         * verbatim. A model that picked a different constant would disagree with every kernel
         * terminal over a global fold, and nothing but this constant would say so.
         */
        const val GLOBAL_KEY: String = "global"

        /** `GroupByCell.global(aggregator)` — one constant-key group (`[24-OP-GROUPBY-01]`). */
        fun global(aggregate: AggregateFunction): GroupByModel = GroupByModel(ConstantKey(GLOBAL_KEY), aggregate)
    }

    /** [ElementKey] ignoring its element — the fold-to-scalar case, as a serializable named type. */
    private data class ConstantKey(val key: Any?) : ElementKey {
        override fun keyOf(element: Any?): Any? = key
        override fun toString(): String = "constant($key)"
    }
}
