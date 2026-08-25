package civictech.oracle.model

import java.io.Serializable

/**
 * The batch denotations of the **set-shaped binary family** of `ORA1 §MODEL-02`'s vocabulary:
 * `IntersectSetCell`, `JoinSetCell` and `SemiJoinCell` in both polarities. The map-shaped join
 * family is [JoinModel]/[CombineLatestModel]/[LookupJoinModel] (`MapJoinModels.kt`), and the
 * grouped-aggregation family is [GroupByModel] (`GroupByModel.kt`).
 *
 * ## Total recomputation, and why that is the point here
 *
 * Every model in this file recomputes its whole output from its inputs' **live sets**. None
 * mirrors the kernel's insert/retract stream, and none has a notion of a tag
 * (`ORA1 §MODEL-03`) — which matters most precisely in this family, because these are the
 * operators whose kernel implementations *mint* tags rather than forwarding input ones
 * (`[24-OP-JOINSET-01]`, `[24-OP-SEMIJOIN-02]`). `ORA1 §MODEL-07` states the consequence as a
 * requirement: a minted-tag operator is modelled as **the set of live combinations**, never as
 * tag identity. The kernel needs one minted tag per live pair so that a pair which re-enters
 * after a removed row returns is not left dead under tombstone folding; the model needs
 * nothing at all, because a recomputation has no history to be poisoned by. That asymmetry is
 * epic computenet-4ru's design D2 — a model that reproduced the mint ledger would agree with
 * the implementation about a shared bug.
 *
 * ## Why `emitOnFrontier` is absent from this file
 *
 * `SemiJoinCell(emitOnFrontier = true)` (`[24-OP-SEMIJOIN-04]`) changes **when** the cell
 * emits, not what is observable once both inputs are idle: the gate coalesces a wave to its
 * net enter/exit set, so a transient enter-then-exit never reaches the outlet, and a settled
 * membership is unchanged either way. A batch reference is a statement about quiescence
 * (`[24-OP-SEMIJOIN-03]`: "WHILE both input streams are idle, output membership SHALL be a
 * deterministic function of the converged add-wins input memberships"), so the flag has no
 * model counterpart, and both polarities register once — see `CoreOperators` for the same
 * statement at the registration site.
 */

// ---------------------------------------------------------------------------
// Identity join
// ---------------------------------------------------------------------------

/**
 * `IntersectSetCell` — the elements live on **both** sides (`[24-OP-INTERSECT-01]`).
 *
 * The requirement itself is entirely about tags: advertise an element's entry tags on entry,
 * delete all advertised tags on exit, absorb tag churn that does not flip membership. Its
 * observable consequence — and the cell KDoc's own opening sentence — is plain set
 * intersection, which is all a membership-only model can and should say. "Absorbs tag churn"
 * is invisible here by construction: a script cannot express tag churn, so there is nothing to
 * absorb.
 *
 * Binary, matching the cell's `left`/`right` inlets; the cell is n-ary only by chaining, so
 * the model deliberately is not.
 */
object IntersectSetModel : OperatorModel, Serializable {
    override fun evaluate(inputs: List<ModelState>): ModelState {
        val (left, right) = binarySets(inputs, "IntersectSetModel")
        return ModelState.SetState(left.elements.filterTo(LinkedHashSet()) { it in right.elements })
    }

    override fun toString(): String = "IntersectSetModel"
}

// ---------------------------------------------------------------------------
// Keyed set joins (minted-tag operators)
// ---------------------------------------------------------------------------

/**
 * `JoinSetCell` / `joinSet` / `crossProduct` — the relational equi-join
 * (`[24-OP-JOINSET-01]`, `[24-OP-JOINSET-02]`, `ORA1 §MODEL-07`):
 *
 * ```
 * { combine(a, b) | a in liveLeft, b in liveRight, leftKey(a) == rightKey(b) }
 * ```
 *
 * Three clauses of the requirement collapse into that one set comprehension, and it is worth
 * naming which, because each is a place the kernel needs machinery and the model needs none:
 *
 * - *"minting one tag per live pair such that a pair re-enters when a removed row returns"* —
 *   a recomputation has no ledger to re-enter into. The pair is either live now or it is not.
 * - *"many-to-one `combine` outputs SHALL survive until their last contributing pair dies
 *   (per-pair tag collapse, not whole-element deletion)"* — set union **is** that survival
 *   rule: two live pairs whose `combine` collides contribute one element, and it disappears
 *   exactly when the last of them does. The divergent naive form the requirement warns about
 *   (deleting the whole output element when one pair exits) is unrepresentable here.
 * - *"many-to-many keys SHALL yield all pairs; cross product SHALL be the unit-key case"* —
 *   the comprehension ranges over every matching pair, and a constant [ElementKey] is the
 *   cross product.
 *
 * [combine] must be pure and [Serializable]; so must both keys. The output element domain is
 * whatever [combine] produces — the model never inspects it.
 */
class JoinSetModel(
    private val leftKey: ElementKey,
    private val rightKey: ElementKey,
    private val combine: ElementCombiner,
) : OperatorModel, Serializable {

    override fun evaluate(inputs: List<ModelState>): ModelState {
        val (left, right) = binarySets(inputs, "JoinSetModel")
        val rightByKey = LinkedHashMap<Any?, MutableList<Any?>>()
        right.elements.forEach { b -> rightByKey.getOrPut(rightKey.keyOf(b)) { mutableListOf() } += b }

        val combinations = LinkedHashSet<Any?>()
        left.elements.forEach { a ->
            rightByKey[leftKey.keyOf(a)]?.forEach { b -> combinations += combine.combine(a, b) }
        }
        return ModelState.SetState(combinations)
    }

    override fun toString(): String = "JoinSetModel($leftKey, $rightKey, $combine)"
}

/**
 * `SemiJoinCell` / `differenceSet` — keyed semijoin (`A ⋉ B`) and antijoin (`A ▷ B`)
 * (`[24-OP-SEMIJOIN-01]`, `[24-OP-SEMIJOIN-03]`, `ORA1 §MODEL-07`):
 *
 * ```
 * { a | a in liveLeft, (leftKey(a) in liveRightKeys) xor negated }
 * ```
 *
 * The `xor` is the cell's own reconciliation predicate verbatim
 * (`SemiJoinCell.reconcile`: `a in leftState && ((leftKey(a) in rightIndex) xor negated)`),
 * and `rightIndex` holds a key exactly while some live right row projects to it — the shared
 * `KeyedBinarySetJoin.index` drops a key whose last row dies — so "the key is in the index" is
 * "the key is among the live right rows' keys", which is what the model computes.
 *
 * **Negated polarity is where this family stops being monotone (BS-5).** An arrival on the
 * *right* retracts a left element that was live: the output shrinks because something was
 * added. That is `[24-OP-SEMIJOIN-01]`'s difference (`A ⊖ B`, the antijoin on identity keys)
 * and it is the single most informative case in this task's slice — a differential runner that
 * disagreed here would be reporting a real non-monotone bug rather than a modelling artefact.
 * `SemiJoinRetractionTest` is its named model-level test.
 *
 * Both polarities are one class and one registration each, because `ORA1 §MODEL-02` names
 * semijoin and antijoin separately while the kernel is one cell with a `negated` flag.
 */
class SemiJoinModel(
    private val leftKey: ElementKey,
    private val rightKey: ElementKey,
    private val negated: Boolean,
) : OperatorModel, Serializable {

    override fun evaluate(inputs: List<ModelState>): ModelState {
        val (left, right) = binarySets(inputs, "SemiJoinModel")
        val liveRightKeys = right.elements.mapTo(HashSet()) { rightKey.keyOf(it) }
        return ModelState.SetState(
            left.elements.filterTo(LinkedHashSet()) { (leftKey.keyOf(it) in liveRightKeys) xor negated },
        )
    }

    override fun toString(): String =
        "SemiJoinModel($leftKey, $rightKey, ${if (negated) "antijoin" else "semijoin"})"
}

/** The two set-shaped inputs of a binary operator, in [civictech.oracle.bind.ShapeRule] port order. */
private fun binarySets(
    inputs: List<ModelState>,
    context: String,
): Pair<ModelState.SetState, ModelState.SetState> {
    require(inputs.size == 2) { "$context is binary (left, right); got ${inputs.size} inputs" }
    return inputs[0].asSet("$context left") to inputs[1].asSet("$context right")
}

// ---------------------------------------------------------------------------
// The function carriers
// ---------------------------------------------------------------------------

/*
 * Named Serializable interfaces rather than raw Kotlin function types, for the reason
 * SetOperatorModels.kt's carriers give: a `(Any?) -> Any?` captured in a model is a
 * `kotlin.jvm.functions.Function1`, which is NOT serializable, and the failure would surface
 * only when a recorded case was first written to disk — far from the registration that caused
 * it. Implementations are `object`s or data classes over serializable state; the canonical
 * instances are `civictech.oracle.bind.CoreOperators`' static tables.
 */

/**
 * A pure, serializable key projection over an element — the `leftKey`/`rightKey` of the join
 * family, `GroupByCell`'s `keyFn`, and `LookupJoinCell`'s foreign key `fk`.
 *
 * One carrier for all four because they are one thing: a total function from a value to the
 * value its operator matches or groups on. Implementations must be **total** over whatever
 * element domain a generated case picks — see `CoreOperators.Keys` for why a projection that
 * threw on an unexpected domain would make the registration, rather than the operator under
 * test, the thing that fails a sweep.
 */
fun interface ElementKey : Serializable {
    fun keyOf(element: Any?): Any?
}

/** A pure, serializable binary combination — `JoinSetCell`'s `combine(a, b)`. */
fun interface ElementCombiner : Serializable {
    fun combine(left: Any?, right: Any?): Any?
}
