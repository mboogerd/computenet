package civictech.oracle.run

import civictech.oracle.model.AggregateFunction
import civictech.oracle.model.ElementKey
import civictech.oracle.model.LongSelector
import civictech.oracle.model.ModelState
import civictech.oracle.model.OperatorModel
import civictech.oracle.model.ScriptEvent
import civictech.oracle.model.SourceModel
import civictech.oracle.model.SourceScript
import civictech.oracle.model.asSet
import java.io.Serializable

/**
 * The deliberately WRONG reference models BS-13 (`[ORA1-DIFF-10]`) measures the differential
 * machinery's power with — see [MutationCheckTest] for what each one is used to prove.
 *
 * **Everything in this file is test scope and must stay there.** None of it is ever registered
 * into the shipped catalog: [MutationCheckTest] substitutes one of these for exactly one
 * catalog id inside one test method and restores the catalog with `OperatorCatalog.reset()` in
 * `@AfterEach`, because the catalog is a process-wide singleton and a leaked mutant
 * registration would silently corrupt every later test in the same JVM.
 *
 * A mutant here is a *plausible* implementation error, never an obviously-broken one. A model
 * that returned a phantom element would make the sweep fail on every seed and prove nothing
 * about whether the sweep can catch a realistic bug; each mutant below is a mistake somebody
 * could actually write.
 */

// ---------------------------------------------------------------------------
// The group-by mutant (prong 1)
// ---------------------------------------------------------------------------

/**
 * The BS-13 mutant: a group-by whose groups are **never removed**, so a group survives its own
 * last retraction — with the aggregator's identity as its value, which is the shape
 * `GroupByModel`'s KDoc names as the real and tempting kernel bug ("leaving the key present
 * with the aggregator's identity (`0` for a sum) reads as harmless and is not").
 *
 * ## Why this mutant is BINARY when the model it mutates is unary
 *
 * `GroupByModel` is unary: it takes the input node's **live** set and derives the result's key
 * set from it, which is precisely why `[ORA1-MODEL-06]` can say group death is *structurally
 * unrepresentable* in the model — a key with no live element is never created.
 *
 * That is not a detail to work around; it is the property being exploited. A group that "keeps
 * a group after its last retraction" has to know that a group once existed, and a pure unary
 * function of the live set **cannot** know that: the information is not in its input. So the
 * mutant is given a second input — a retraction-blind element set, [EverAddedSourceModel] over
 * the same source — and stays pure. The extra port is the mutation's cost of admission, and it
 * is direct evidence for `[ORA1-MODEL-06]`: making this bug expressible at all required
 * widening the operator's interface.
 *
 * Port order (`ShapeRule.inputs` order, which `ReferenceModel` honours):
 *
 * - input 0 — the **live** set. Decides every group's *value*.
 * - input 1 — the **ever-added** set. Decides every group's *key*, and only that.
 *
 * A group with no live member therefore has an empty member collection, and its value is what
 * [aggregate] returns for an empty collection (`0L` for `Aggregates.sumOf`) — the stale-identity
 * shape, not an absent key.
 */
class RetentiveGroupByModel(
    private val keyFn: ElementKey,
    private val aggregate: AggregateFunction,
) : OperatorModel, Serializable {

    override fun evaluate(inputs: List<ModelState>): ModelState {
        require(inputs.size == 2) {
            "RetentiveGroupByModel is binary — port 0 the live set, port 1 the ever-added set; " +
                "got ${inputs.size} inputs"
        }
        val live = inputs[0].asSet("RetentiveGroupByModel(live)")
        val everAdded = inputs[1].asSet("RetentiveGroupByModel(everAdded)")

        // The key set comes from the retraction-blind input: once a group has existed it is
        // never removed. THIS is the mutation.
        val groups = LinkedHashMap<Any?, MutableList<Any?>>()
        everAdded.elements.forEach { element -> groups.getOrPut(keyFn.keyOf(element)) { mutableListOf() } }
        live.elements.forEach { element -> groups.getOrPut(keyFn.keyOf(element)) { mutableListOf() } += element }

        return ModelState.MapState(groups.mapValues { (_, members) -> aggregate.aggregate(members) })
    }

    override fun toString(): String = "RetentiveGroupByModel($keyFn, $aggregate)"
}

/**
 * A retraction-blind source: every element the slice ever **added**, whether it is still live
 * or not.
 *
 * Not a mutant on its own — nothing compares a terminal against it. It exists only to carry
 * [RetentiveGroupByModel]'s second input, because the honest membership fold deliberately
 * discards exactly the fact that mutant needs (see its KDoc).
 */
object EverAddedSourceModel : SourceModel, Serializable {
    override fun evaluate(slice: SourceScript): ModelState = ModelState.SetState(
        slice.events.filterIsInstance<ScriptEvent.Add>().mapTo(LinkedHashSet()) { it.element },
    )

    override fun toString(): String = "EverAddedSourceModel"
}

/** `keyOf("a1") == "a"` — the group key both halves of [MutationCheckTest]'s hand-built case share. */
object FirstCharacterKey : ElementKey, Serializable {
    override fun keyOf(element: Any?): Any? = (element as String).take(1)
    override fun toString(): String = "firstCharacter"
}

/** `selectLong("a12") == 12L` — the summed projection both halves of that case share. */
object TrailingDigitsAsLong : LongSelector, Serializable {
    override fun selectLong(element: Any?): Long = (element as String).drop(1).toLong()
    override fun toString(): String = "trailingDigitsAsLong"
}

// ---------------------------------------------------------------------------
// The fan-in mutant (prong 2)
// ---------------------------------------------------------------------------

/**
 * The mutant substituted for the generator-reachable `union` id: a union that **forgets its
 * last arm**.
 *
 * The plausibility argument matters, because a mutation check measures whether the sweep can
 * catch a realistic bug: `UnionSetModel` folds an unbounded arm list, and an off-by-one in
 * such a fold — `inputs.dropLast(1)`, a `for (i in 0 until inputs.size - 1)` — is an ordinary
 * mistake that type-checks, keeps the output shape, and is right whenever the forgotten arm
 * happens to contribute nothing.
 *
 * ## Why not the mutant BS-13's prose names ("ignores retractions")
 *
 * An [OperatorModel] sees its inputs' **live sets** and nothing else (`ReferenceOp`'s
 * evaluation contract: "it never sees the script"). A retraction is not in that input, so
 * "ignores retractions" is not expressible for a derived operator at all — the same
 * unrepresentability [RetentiveGroupByModel] had to buy its way out of with an extra port,
 * and there is no honest way to buy it here: the mutant has to keep `union`'s registered
 * `ShapeRule` verbatim (two `SetOf(Scalar)` arms) or it is no longer a substitution for that
 * id. A wrong pure function of the live arms is what remains, and dropping an arm is the most
 * plausible one.
 */
object ForgetfulUnionModel : OperatorModel, Serializable {
    override fun evaluate(inputs: List<ModelState>): ModelState = ModelState.SetState(
        inputs.dropLast(1).flatMapTo(LinkedHashSet()) { it.asSet("ForgetfulUnionModel").elements },
    )

    override fun toString(): String = "ForgetfulUnionModel"
}
