package civictech.oracle.run

import civictech.oracle.model.AggregateFunction
import civictech.oracle.model.DotOrder
import civictech.oracle.model.ElementKey
import civictech.oracle.model.LongSelector
import civictech.oracle.model.ModelDot
import civictech.oracle.model.ModelState
import civictech.oracle.model.OperatorModel
import civictech.oracle.model.Script
import civictech.oracle.model.ScriptEvent
import civictech.oracle.model.SourceId
import civictech.oracle.model.SourceModel
import civictech.oracle.model.SourceScript
import civictech.oracle.model.asSet
import java.io.Serializable

/**
 * The deliberately WRONG reference models BS-13 (`ORA1 §DIFF-10`) measures the differential
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
 * set from it, which is precisely why `ORA1 §MODEL-06` can say group death is *structurally
 * unrepresentable* in the model — a key with no live element is never created.
 *
 * That is not a detail to work around; it is the property being exploited. A group that "keeps
 * a group after its last retraction" has to know that a group once existed, and a pure unary
 * function of the live set **cannot** know that: the information is not in its input. So the
 * mutant is given a second input — a retraction-blind element set, [EverAddedSourceModel] over
 * the same source — and stays pure. The extra port is the mutation's cost of admission, and it
 * is direct evidence for `ORA1 §MODEL-06`: making this bug expressible at all required
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

// ---------------------------------------------------------------------------
// ORA2's tagged-map mutants — `ORA2 §CTL-01`/BS-13, `ORA2 §CTL-03`/BS-4
// ---------------------------------------------------------------------------
//
// Used by `civictech.oracle.tagged.TaggedControlsTest`, never registered into
// `civictech.oracle.bind.OperatorCatalog` — same test-scope-only rule the file KDoc above
// states. `civictech.oracle.model.DotModel` cannot be registered as a catalog entry for the
// replicated-mesh case at all (`civictech.oracle.bind.TaggedOperators`'s file KDoc: its
// cross-instance merge needs the whole multi-instance `Script`, which the catalog's per-node
// evaluation shapes cannot supply), so these two mutants are compared against `DotModel`
// directly rather than substituted through `DifferentialRunner` — the same "drive
// `DotModel.converged`/`stateOf` directly" idiom the feature design assigns the replicated
// differential itself.

/**
 * The `ORA2 §CTL-01`/BS-13 mutant: an **untagged**, per-replica arrival-order fold over an
 * OR-map script — no dot minted, no reset-remove tombstoning, a plain last-write(-or-delivery)
 * -wins map at each replica. The plausible bug it stands in for: reading `OrMapCell`'s
 * observable as if it were `MapCell`/`MapDelta` replicated verbatim, resolving every put and
 * every gossiped delivery by arrival order alone rather than by dot identity.
 *
 * Traverses the **same** event/delivery interleaving [civictech.oracle.model.DotModel]'s private
 * `Fold` does — a source's own events interleaved with its [civictech.oracle.model.Delivery]s in
 * `afterEvents` order, ties broken by `(from.id, throughEvents)` — so the two folds are
 * comparable move for move; only what each move DOES to the state differs. A [Delivery] here is
 * not a merge of dot sets, it is the peer's current map **overwriting** this one key by key,
 * with no notion of "which write is causally later" at all.
 */
object NaiveArrivalOrderMapModel {

    /** [source]'s own naive fold — no dot semantics, no add-wins: the last write at each key wins. */
    fun stateOf(script: Script, source: SourceId): Map<Any?, Any?> =
        Fold(script).stateAfter(source, script.slice(source).events.size)

    /** Every source's own naive fold, in slice order — the "untagged read" BS-13 compares against [DotModel]. */
    fun perInstance(script: Script): Map<SourceId, Map<Any?, Any?>> =
        script.sources().associateWith { stateOf(script, it) }

    private class Fold(private val script: Script) {
        private val memo = HashMap<Pair<SourceId, Int>, Map<Any?, Any?>>()

        fun stateAfter(source: SourceId, prefix: Int): Map<Any?, Any?> {
            val slice = script.slice(source)
            val at = source to prefix
            memo[at]?.let { return it }
            var state: Map<Any?, Any?> = emptyMap()
            for (position in 0..prefix) {
                state = applyDeliveries(slice, position, state)
                if (position == prefix) break
                state = apply(slice.events[position], state)
            }
            memo[at] = state
            return state
        }

        private fun applyDeliveries(slice: SourceScript, position: Int, state: Map<Any?, Any?>): Map<Any?, Any?> =
            slice.deliveries.filter { it.afterEvents == position }
                .sortedWith(compareBy({ it.from.id }, { it.throughEvents }))
                .fold(state) { acc, delivery ->
                    // The mutation, entirely: the peer's current view overwrites ours key by
                    // key — arrival order only, no dot, no causal comparison.
                    LinkedHashMap(acc).apply { putAll(stateAfter(delivery.from, delivery.throughEvents)) }
                }

        private fun apply(event: ScriptEvent, state: Map<Any?, Any?>): Map<Any?, Any?> = when (event) {
            is ScriptEvent.Put -> LinkedHashMap(state).apply { this[event.key] = event.element }
            is ScriptEvent.RemoveKey -> LinkedHashMap(state).apply { remove(event.key) }
            else -> state
        }
    }

    override fun toString(): String = "NaiveArrivalOrderMapModel"
}

/**
 * The `ORA2 §CTL-03`/BS-4 mutant: [civictech.oracle.model.DotState.resetRemove] replaced by
 * **remove-all**. The real reset-remove tombstones only the dots THIS instance currently holds
 * live at a key — add-wins, `[24-TMAP-04]`: a dot minted concurrently at another instance and
 * merged in later survives. This mutant instead marks the key **permanently wiped** the moment
 * any instance removes it, regardless of what that instance had actually observed there — the
 * plausible bug of reading "remove(k)" as a boolean tombstone on the key rather than a per-dot
 * one.
 *
 * Reuses the real [ModelDot] and [DotOrder] — the dot identity and its tie-break are untouched;
 * the ONLY thing this mutates is which dots a remove kills. `RemoveAllDotModel.value` is `null`
 * for any wiped key, which is what makes BS-3/BS-4's scenario — writer A's second, unobserved
 * put surviving writer B's reset-remove — come out wrong here: once B removes the key, this
 * mutant wipes it for good, so A's later concurrent put at the same key never resurrects it.
 */
class RemoveAllDotModel(private val order: DotOrder) {

    /** [source]'s own state, mutant semantics, after its whole log. */
    fun stateOf(script: Script, source: SourceId): State =
        Fold(script).stateAfter(source, script.slice(source).events.size)

    /** The converged state across every instance: a key wiped by ANY instance stays wiped. */
    fun converged(script: Script): State =
        script.sources().map { stateOf(script, it) }.fold(State()) { acc, s -> acc.merge(s) }

    /** The value at [key] — `null` if wiped or never put, the winner by [order] otherwise. */
    fun value(state: State, key: Any?): Any? {
        if (key in state.wiped) return null
        val live = state.puts[key] ?: return null
        return live.entries.maxWithOrNull(compareBy(order.comparator()) { it.key })?.value
    }

    /** The mutant's state: every dot ever put, and the keys a remove-all has wiped. */
    data class State(
        val puts: Map<Any?, Map<ModelDot, Any?>> = emptyMap(),
        val wiped: Set<Any?> = emptySet(),
    ) {
        fun merge(other: State): State {
            val mergedPuts = LinkedHashMap<Any?, Map<ModelDot, Any?>>()
            (puts.keys + other.puts.keys).forEach { key ->
                val left = puts[key]
                val right = other.puts[key]
                mergedPuts[key] = when {
                    left == null -> right!!
                    right == null -> left
                    else -> LinkedHashMap(left).also { it.putAll(right) }
                }
            }
            return State(mergedPuts, wiped + other.wiped)
        }

        fun put(key: Any?, dot: ModelDot, value: Any?): State {
            val nextPuts = LinkedHashMap(puts)
            nextPuts[key] = LinkedHashMap(puts[key] ?: emptyMap()).also { it[dot] = value }
            return State(nextPuts, wiped)
        }

        /** THE mutation: wipe [key] entirely, rather than tombstoning only the observed dots. */
        fun removeAll(key: Any?): State = State(puts, wiped + key)
    }

    private inner class Fold(private val script: Script) {
        private val memo = HashMap<Pair<SourceId, Int>, State>()

        fun stateAfter(source: SourceId, prefix: Int): State {
            val slice = script.slice(source)
            val at = source to prefix
            memo[at]?.let { return it }
            var state = State()
            var counter = 0L
            for (position in 0..prefix) {
                state = applyDeliveries(slice, position, state)
                if (position == prefix) break
                val event = slice.events[position]
                if (event is ScriptEvent.Put) counter += 1
                state = apply(event, source, counter, state)
            }
            memo[at] = state
            return state
        }

        private fun applyDeliveries(slice: SourceScript, position: Int, state: State): State =
            slice.deliveries.filter { it.afterEvents == position }
                .sortedWith(compareBy({ it.from.id }, { it.throughEvents }))
                .fold(state) { acc, delivery -> acc.merge(stateAfter(delivery.from, delivery.throughEvents)) }

        private fun apply(event: ScriptEvent, source: SourceId, counter: Long, state: State): State =
            when (event) {
                is ScriptEvent.Put -> state.put(event.key, ModelDot(counter, source), event.element)
                is ScriptEvent.RemoveKey -> state.removeAll(event.key)
                else -> state
            }
    }

    override fun toString(): String = "RemoveAllDotModel($order)"
}
