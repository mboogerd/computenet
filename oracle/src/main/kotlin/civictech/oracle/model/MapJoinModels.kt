package civictech.oracle.model

import java.io.Serializable

/**
 * The batch denotations of the **map-shaped join family**: `JoinCell` (keyed inner join),
 * `CombineLatestCell` (keyed outer combine) and `LookupJoinCell` (foreign-key / dimension
 * join). Their set-shaped siblings are in `JoinModels.kt`.
 *
 * ## Where each denotation comes from
 *
 * Each of the three cells maintains its output incrementally and rebuilds it by **total
 * recomputation over its settled inputs** on `restore` — `CombineLatestCell.restore`
 * recomputes every key of `leftMap ∪ rightMap`, `LookupJoinCell.restore` recomputes every
 * fact. Those rebuild paths are the kernel's own statement of what its output *is* at rest,
 * independent of how it got there, and they are what the models below compute. That is not
 * borrowing the implementation: the model reaches the same value from the input states alone,
 * with none of the reverse index, publisher or effective-only gating the cells carry, so a
 * disagreement is informative (epic computenet-4ru design D2).
 *
 * ## Arrival order, and what the model may therefore say
 *
 * `MapDelta` carries no causal tags (G-23), so **concurrent same-key puts resolve by arrival
 * order** — `[24-OP-JOIN-01]` says as much in the requirement itself ("two single-writer map
 * streams where either side's put refreshes the pair under value-replacement (arrival-order)
 * semantics"). That limit belongs to whatever *produces* a map stream, not to these operators:
 * given two settled [ModelState.MapState]s, each of the three is a deterministic, pure
 * function of them, with no order left to depend on. `[ORA1-MODEL-08]`'s single-writer-FIFO
 * restriction is therefore documented at the registration site of the map **source**
 * (`MapCell`, computenet-4ru.5.3) and repeated on these entries in `CoreOperators`, rather
 * than weakening the operator denotations here.
 *
 * ## Null lookup versus key presence
 *
 * All three cells read their inputs with a null lookup (`rightMap[k]`, `facts[k] ?: return
 * null`), so a key bound to a `null` **value** would be indistinguishable from an absent key.
 * No operator in the registered vocabulary emits a null map value — `PresenceCountCell` emits
 * counts, `GroupByCell` aggregates, and these three drop a key whose `combine` returns null —
 * so key presence and a non-null lookup coincide on every map stream a case can build, and the
 * models below use key presence. A null-valued map entry is outside what this reference
 * defines rather than something it approximates.
 */

/**
 * `JoinCell` — the keyed inner join (`[24-OP-JOIN-01]`):
 *
 * ```
 * { k -> Pair(left[k], right[k]) | k in leftKeys ∩ rightKeys }
 * ```
 *
 * "either side's put *refreshes* the pair" is an incremental statement of the same thing: at
 * quiescence each side holds one latest value per key, so the pair is the pair of latest
 * values, and a key either side dropped is absent. `JoinCell.joined()` — which the cell uses
 * for its own late-join catch-up — is this expression verbatim.
 *
 * The output value is a Kotlin [Pair], matching the cell's `MapDelta<K, Pair<V, W>>` outlet:
 * [ModelState] equality is structural, so the modelled value has to be the same shape a
 * terminal over that outlet reads.
 */
object JoinModel : OperatorModel, Serializable {
    override fun evaluate(inputs: List<ModelState>): ModelState {
        val (left, right) = binaryMaps(inputs, "JoinModel")
        val joined = LinkedHashMap<Any?, Any?>()
        left.entries.forEach { (key, value) ->
            if (key in right.entries) joined[key] = value to right.entries[key]
        }
        return ModelState.MapState(joined)
    }

    override fun toString(): String = "JoinModel"
}

/**
 * `CombineLatestCell` — the keyed **outer** combine, the outer sibling of [JoinModel]:
 *
 * ```
 * { k -> combine(k, left[k], right[k]) | k in leftKeys ∪ rightKeys, combine(...) != null }
 * ```
 *
 * Three clauses of the cell's contract, each of which the union-and-filter expression states
 * exactly (spec 24 §Tagged sets, `CombineLatestCell`; the cell carries no EARS id of its own,
 * so its KDoc is the contract — as the cell itself says):
 *
 * - *a key present on only one side still produces output, computed as `combine(k, v, null)` /
 *   `combine(k, null, w)`* — the union ranges over it, and the absent side reads `null`.
 * - *`combine` returning `null` drops the key* — the filter.
 * - *a key absent from both sides is removed regardless of what `combine` would return, so
 *   there are no ghost keys* — such a key is not in the union, so `combine` is never asked
 *   about it. The kernel guards this explicitly (`if (k in leftMap || k in rightMap)`); here it
 *   is structural.
 *
 * **`emitOnFrontier` has no model counterpart**, for the reason `JoinModels.kt` gives for
 * `SemiJoinCell`: the gate decides when a null-extension may ride the outlet, not what the
 * settled output is. A gated and an ungated cell agree at quiescence, which is the only place
 * a batch reference speaks.
 */
class CombineLatestModel(private val combine: KeyedCombiner) : OperatorModel, Serializable {

    override fun evaluate(inputs: List<ModelState>): ModelState {
        val (left, right) = binaryMaps(inputs, "CombineLatestModel")
        val combined = LinkedHashMap<Any?, Any?>()
        (left.entries.keys + right.entries.keys).forEach { key ->
            combine.combine(key, left.entries[key], right.entries[key])?.let { combined[key] = it }
        }
        return ModelState.MapState(combined)
    }

    override fun toString(): String = "CombineLatestModel($combine)"
}

/**
 * `LookupJoinCell` — the foreign-key / dimension join, keyed by the **fact** key:
 *
 * ```
 * { k -> combine(k, facts[k], dimensions[fk(k)]) | k in factKeys, combine(...) != null }
 * ```
 *
 * Port order is `(fact, dimension)`, matching the cell's `fact`/`dimension` inlets. Its
 * contract, again from its own KDoc (no `[24-OP-*]` id covers this cell — see the note below):
 *
 * - *left-outer*: a fact whose dimension row is absent still emits, with the dimension value
 *   `null`. The model asks the dimension map for `fk(k)` and gets `null` when it holds no such
 *   row — no separate branch.
 * - *`combine` returning `null` filters the fact out* — the filter, as in [CombineLatestModel].
 * - *the output key type is the fact key; the dimension is a lookup table, never a key of the
 *   result* — the comprehension ranges over fact keys only.
 * - *reactive on both sides*: a dimension change re-emits every fact referencing it. That is a
 *   statement about which facts the cell must recompute, and a total recomputation recomputes
 *   all of them; the cell's reverse index `byDim` is an efficiency device with no observable
 *   consequence, so the model has none.
 *
 * **A citation note, deliberately recorded here rather than in a dispute.** This task's bead
 * cites `[24-OP-LOOKUP-01]` as spec authority for this cell. No such requirement exists in
 * `doc/spec/20-dataflow-semantics/24-data-cells.md`: `24-OP-LOOKUP-01` is a *concord scenario
 * id* (`concord/corpus/24-data-cells/24-OP-LOOKUP-01.yaml`), whose own header says "Chapter 24
 * has no EARS id specific to lookup-join", and which `covers: [24-OP-JOINSET-01]` because
 * concord's `lookup-join` catalog entry binds to `JoinSetCell` with an enriching `combine` —
 * a *different cell* from this one. So there is no spec-versus-KDoc disagreement to file: the
 * spec is silent about `LookupJoinCell`, the cell's KDoc is its whole contract (the cell says
 * so itself), and this model mirrors that KDoc.
 */
class LookupJoinModel(
    private val foreignKey: ElementKey,
    private val combine: KeyedCombiner,
) : OperatorModel, Serializable {

    override fun evaluate(inputs: List<ModelState>): ModelState {
        val (facts, dimensions) = binaryMaps(inputs, "LookupJoinModel")
        val enriched = LinkedHashMap<Any?, Any?>()
        facts.entries.forEach { (key, value) ->
            combine.combine(key, value, dimensions.entries[foreignKey.keyOf(key)])
                ?.let { enriched[key] = it }
        }
        return ModelState.MapState(enriched)
    }

    override fun toString(): String = "LookupJoinModel($foreignKey, $combine)"
}

/** The two map-shaped inputs of a binary operator, in [civictech.oracle.bind.ShapeRule] port order. */
private fun binaryMaps(
    inputs: List<ModelState>,
    context: String,
): Pair<ModelState.MapState, ModelState.MapState> {
    require(inputs.size == 2) { "$context is binary; got ${inputs.size} inputs" }
    return inputs[0].asMap("$context port 0") to inputs[1].asMap("$context port 1")
}

/**
 * A pure, serializable three-argument combination over a key and the two sides' values —
 * `CombineLatestCell`'s `combine(k, v, w)` and `LookupJoinCell`'s `combine(k, v, d)`.
 *
 * One carrier for both because they share a signature *and* a convention: either side's value
 * may be `null` (the absent side of an outer combine, the missing dimension row of a
 * left-outer lookup), and **returning `null` drops the key from the output**. That drop is the
 * cells' documented group-death / filtering behaviour, not an error channel.
 */
fun interface KeyedCombiner : Serializable {
    fun combine(key: Any?, left: Any?, right: Any?): Any?
}
