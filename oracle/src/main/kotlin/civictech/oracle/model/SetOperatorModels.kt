package civictech.oracle.model

import java.io.Serializable

/**
 * The batch denotations of the set-source, unary and fan-in slice of `[ORA1-MODEL-02]`'s
 * vocabulary — the reference answers a differential run checks the kernel's incremental
 * answers against.
 *
 * Every model here is a **total recomputation** over live state; none mirrors the kernel's
 * insert/retract stream. That is the independence epic computenet-4ru's design D2 asks for:
 * a model that reproduced the incremental machinery would agree with the implementation
 * about a shared bug. `[ORA1-MODEL-03]` binds throughout — nothing below reads a tag, a tag
 * count, a wave id, or a `SetDelta` internal, because none of those is representable in
 * [Script] or [ModelState] in the first place.
 *
 * The binary/keyed-join/group-by family is computenet-4ru.5.2's; `MapCell` and the honesty
 * exclusion ledger are computenet-4ru.5.3's.
 */

// ---------------------------------------------------------------------------
// Sources
// ---------------------------------------------------------------------------

/**
 * `SetCell` — observed-remove membership over the source's own slice (`[24-SET-01]`,
 * `[24-SET-03]`, `[ORA1-MODEL-04]`, `[ORA1-MODEL-05]`). The whole semantics is
 * [Membership.live]; see its KDoc for the rule and for why BS-2 and BS-3 fall out of it.
 */
object SetSourceModel : SourceModel, Serializable {
    override fun evaluate(slice: SourceScript): ModelState = ModelState.SetState(Membership.live(slice))
    override fun toString(): String = "SetSourceModel"
}

/**
 * `KeyedSetCell` — keyed upsert, modelled as the fold of the slice's [ScriptEvent.Put] and
 * [ScriptEvent.RemoveKey] events over a key → element table.
 *
 * **Observable state is the live ELEMENT set**, not the key→element table, because that is
 * what the kernel cell emits: its outlet is `Subscribe<Propagate<SetDelta<E>>>`
 * (kernel/src/main/kotlin/civictech/cell/data/KeyedSetCell.kt), so a terminal downstream of
 * it observes a set of `E`. Two keys holding the same element therefore contribute **one**
 * live element, and it survives until the last key drops it — the cell's own KDoc calls this
 * distinct-projection / OR-set union, and it is the only place the difference is visible.
 * [liveBindings] exposes the table for tests and for any later consumer that wants it.
 *
 * *Divergence, recorded deliberately:* computenet-4ru.5.1's decided direction said "result is
 * the live (key,element) set". A pair-shaped output would be `SetOf(Tuple(2))` and could not
 * be compared against the kernel terminal a differential run actually observes, nor consumed
 * by the `SetOf(Scalar)` operators registered beside it, so the element set is what is
 * modelled and the bead carries the note.
 *
 * Re-put atomicity needs no modelling: a batch fold has no intermediate state to observe, so
 * "retracts-then-adds in one delta" is exactly "the table binds the new element".
 *
 * Order within the slice is the source cell's own arrival order (a cell is a single
 * serialization point), so this fold is well defined under any number of writers — unlike
 * `MapCell`, whose LWW is defined across *concurrent* writers and is therefore restricted to
 * single-writer FIFO by `[ORA1-MODEL-08]` (computenet-4ru.5.3).
 */
object KeyedSetSourceModel : SourceModel, Serializable {

    /** The key → element table this slice leaves behind, in insertion order. */
    fun liveBindings(slice: SourceScript): Map<Any?, Any?> {
        val bindings = LinkedHashMap<Any?, Any?>()
        slice.events.forEach { event ->
            when (event) {
                is ScriptEvent.Put -> bindings[event.key] = event.element
                is ScriptEvent.RemoveKey -> bindings.remove(event.key)
                else -> Unit
            }
        }
        return bindings
    }

    override fun evaluate(slice: SourceScript): ModelState =
        ModelState.SetState(LinkedHashSet(liveBindings(slice).values))

    override fun toString(): String = "KeyedSetSourceModel"
}

/**
 * `CounterCell` — the net total (`[24-OP-COUNTER-01]`).
 *
 * The kernel cell implements `decrement(a)` as `increment(-a)`
 * (kernel/src/main/kotlin/civictech/cell/data/CounterCell.kt), so the batch denotation is
 * the sum of increments minus the sum of decrements. Merge being addition — commutative but
 * not idempotent — is a *replication* property; it does not change what a single instance
 * reads, which is all a batch reference states.
 */
object CounterSourceModel : SourceModel, Serializable {
    override fun evaluate(slice: SourceScript): ModelState = ModelState.ScalarState(netTotal(slice))
    override fun toString(): String = "CounterSourceModel"
}

/**
 * `PnCounterCell` — increments minus decrements (`[24-OP-PNCOUNTER-01]`).
 *
 * Identical batch value to [CounterSourceModel] and deliberately a separate registration:
 * the two cells differ in convergence class (pointwise-max per-source totals vs plain
 * addition), which a *replicated* differential case can distinguish and a single-instance
 * batch fold cannot. Modelling them as one entry would quietly drop `PnCounterCell` from
 * `[ORA1-MODEL-02]`'s coverage list.
 */
object PnCounterSourceModel : SourceModel, Serializable {
    override fun evaluate(slice: SourceScript): ModelState = ModelState.ScalarState(netTotal(slice))
    override fun toString(): String = "PnCounterSourceModel"
}

private fun netTotal(slice: SourceScript): Long =
    slice.events.sumOf { event ->
        when (event) {
            is ScriptEvent.Increment -> event.amount
            is ScriptEvent.Decrement -> -event.amount
            else -> 0L
        }
    }

// ---------------------------------------------------------------------------
// Unary operators
// ---------------------------------------------------------------------------

/**
 * `FilterCell` — the live elements satisfying [predicate] (`[24-OP-FILTER-01]`).
 *
 * "with element tags unchanged" is the kernel's half of that requirement and has no model
 * counterpart by construction (`[ORA1-MODEL-03]`): membership is all the model can see, and
 * the filtered membership is all the requirement constrains observably.
 *
 * [predicate] must be pure and [Serializable] — a registered model rides the same recorded
 * case as the script does.
 */
class FilterModel(private val predicate: ElementPredicate) : OperatorModel, Serializable {
    override fun evaluate(inputs: List<ModelState>): ModelState {
        val input = single(inputs, "FilterModel").asSet("FilterModel")
        return ModelState.SetState(input.elements.filterTo(LinkedHashSet(), predicate::test))
    }

    override fun toString(): String = "FilterModel($predicate)"
}

/**
 * `FlatMapSetCell` / `mapSet` — the union of [transform]'s images over the live input set
 * (`[24-OP-FLATMAP-01]`, `[24-OP-FLATMAP-02]`).
 *
 * The requirement's operative clause — colliding outputs union their preimages' tag sets, so
 * an output stays live until its last live preimage dies — *is* set union in the model: an
 * output is live iff some live preimage maps to it. That the kernel needs a tag-set union to
 * get there and the model needs nothing is the independence D2 is after, not a gap.
 *
 * `mapSet(f)` is `FlatMapSetCell(f = { listOf(f(it)) })` in the kernel
 * (kernel/src/main/kotlin/civictech/cell/data/op/FlatMapSetCell.kt:88), so it is this model
 * with a singleton-image [transform], registered separately because `[ORA1-MODEL-02]` names
 * it separately.
 */
class FlatMapSetModel(private val transform: ElementExpansion) : OperatorModel, Serializable {
    override fun evaluate(inputs: List<ModelState>): ModelState {
        val input = single(inputs, "FlatMapSetModel").asSet("FlatMapSetModel")
        return ModelState.SetState(input.elements.flatMapTo(LinkedHashSet()) { transform.expand(it) })
    }

    override fun toString(): String = "FlatMapSetModel($transform)"
}

/**
 * `CountCell` — the number of distinct live elements, as a scalar (`[24-OP-COUNT-01]`).
 *
 * "emits only when membership size changes" is an emission rule the batch reference cannot
 * and need not express: at quiescence the observable is the count, and effective-only
 * emission is what makes the kernel's count equal it.
 */
object CountModel : OperatorModel, Serializable {
    override fun evaluate(inputs: List<ModelState>): ModelState =
        ModelState.ScalarState(single(inputs, "CountModel").asSet("CountModel").elements.size)

    override fun toString(): String = "CountModel"
}

// ---------------------------------------------------------------------------
// Fan-in operators
// ---------------------------------------------------------------------------

/**
 * `UnionSetCell` — the union of every input arm's live set (`[24-OP-UNION-01]`).
 *
 * The requirement is about tag bookkeeping (track live tags per element, forward only new
 * tag information, so a diamond fan-in dedups). Its *observable* consequence is plain set
 * union, and a model that computed set union while the kernel double-counted a redelivered
 * tag would disagree — which is exactly how BS-4 detects the failure.
 *
 * N-ary at any arity, including zero arms (the empty union is the empty set).
 */
object UnionSetModel : OperatorModel, Serializable {
    override fun evaluate(inputs: List<ModelState>): ModelState =
        ModelState.SetState(inputs.flatMapTo(LinkedHashSet()) { it.asSet("UnionSetModel").elements })

    override fun toString(): String = "UnionSetModel"
}

/**
 * `PresenceCountCell` — per element, the number of distinct input arms whose live set
 * contains it, as a map.
 *
 * Verified against the kernel cell's KDoc (kernel/src/main/kotlin/civictech/cell/data/op/
 * PresenceCountCell.kt): "the number of distinct live source links currently asserting it",
 * with `removal(e)` when the count drops to 0 — so an element asserted by no arm is
 * **absent** from the map rather than present with 0, the same group-death rule
 * `[24-OP-GROUPBY-02]` states for `GroupByCell`. A batch recomputation gets that for free by
 * never inserting a zero.
 *
 * N-ary at any arity. An arm that is live but asserts nothing contributes to no element's
 * count, which is why the model needs no notion of "open lane": at quiescence an empty arm
 * and an absent arm are observationally identical *for this cell*. [QuorumSetModel] is where
 * that distinction becomes real, because its threshold reads the arm count.
 */
object PresenceCountModel : OperatorModel, Serializable {
    override fun evaluate(inputs: List<ModelState>): ModelState =
        ModelState.MapState(presenceCounts(inputs, "PresenceCountModel"))

    override fun toString(): String = "PresenceCountModel"
}

/**
 * `QuorumSetCell` — the elements whose presence count meets [threshold], evaluated against
 * the **number of input arms** (the kernel's live-source count `n`).
 *
 * The kernel's threshold is `(liveSources: Int) -> Int`, so the whole quorum family — union
 * `{ 1 }`, intersection `{ n -> n }`, majority `{ n -> n / 2 + 1 }`, k-of-n `{ k }` — is one
 * model. Output tag discipline (advertise on entry, delete exactly the advertised tags on
 * exit) is the kernel's means of making membership precise and has no model counterpart
 * (`[ORA1-MODEL-03]`).
 *
 * **Where the model is knowingly coarser than the cell**, and why it is still an honest
 * reference: the kernel's `n` is the count of currently *open links*, which a topology event
 * can move at runtime, and `[24-REPLAY-01]` admits a `baseline`-stamped delivery regardless
 * of the threshold. Neither is expressible in a script — a script drives sources, not the
 * link graph — so the model's `n` is the graph's static arm count and recovery deliveries do
 * not occur. A generated case that opened or closed a link under a quorum, or replayed one,
 * would be outside what this reference defines; keeping such cases out is the generator's
 * job (computenet-4ru.6), not something to approximate here.
 */
class QuorumSetModel(private val threshold: QuorumThreshold) : OperatorModel, Serializable {
    override fun evaluate(inputs: List<ModelState>): ModelState {
        val counts = presenceCounts(inputs, "QuorumSetModel")
        val required = threshold.required(inputs.size)
        return ModelState.SetState(counts.filterValues { it >= required }.keys.toCollection(LinkedHashSet()))
    }

    override fun toString(): String = "QuorumSetModel($threshold)"
}

/** Element → number of input arms asserting it. Elements asserted by no arm are absent. */
private fun presenceCounts(inputs: List<ModelState>, context: String): Map<Any?, Int> {
    val counts = LinkedHashMap<Any?, Int>()
    inputs.forEach { arm ->
        arm.asSet(context).elements.forEach { element -> counts[element] = (counts[element] ?: 0) + 1 }
    }
    return counts
}

private fun single(inputs: List<ModelState>, context: String): ModelState {
    require(inputs.size == 1) { "$context is unary; got ${inputs.size} inputs" }
    return inputs[0]
}

// ---------------------------------------------------------------------------
// The function carriers
// ---------------------------------------------------------------------------

/*
 * A registered model rides a recorded case, so every function an operator is configured with
 * has to be Serializable. These are named [Serializable] interfaces rather than raw Kotlin
 * function types because a `(Any?) -> Boolean` captured in a model is a `kotlin.jvm.functions
 * .Function1`, which is NOT serializable — the failure would surface only when a case was
 * first written to disk, far from the registration that caused it. Implementations are
 * expected to be `object`s or data classes over serializable state; see
 * `civictech.oracle.bind.CoreOperators`, whose static tables are the canonical instances.
 */

/** A pure, serializable element predicate — `FilterCell`'s configuration. */
fun interface ElementPredicate : Serializable {
    fun test(element: Any?): Boolean
}

/** A pure, serializable element expansion — `FlatMapSetCell`'s / `mapSet`'s configuration. */
fun interface ElementExpansion : Serializable {
    fun expand(element: Any?): Iterable<Any?>
}

/** A pure, serializable quorum threshold over the arm count — `QuorumSetCell`'s configuration. */
fun interface QuorumThreshold : Serializable {
    fun required(arms: Int): Int
}
