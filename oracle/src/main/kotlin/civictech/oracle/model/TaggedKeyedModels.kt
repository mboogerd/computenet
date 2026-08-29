package civictech.oracle.model

import java.io.Serializable

/**
 * The three reference models ORA2 adds beside [DotModel]: `KeyedSetCell`'s per-key re-put
 * atomicity (`ORA2 §MODEL-08`), `MergeableGroupByCell`'s grow/merge-only aggregation
 * (`ORA2 §MODEL-09`), and `PnCounterCell`'s per-source pointwise-max totals
 * (`ORA2 §MODEL-10`).
 *
 * Same rules as every other file in this package: pure folds over a [Script], no kernel cell
 * executed, no `civictech.cell.data.op` type and no concrete data-cell class referenced
 * (`ORA2 §MODEL-11`, enforced by `ModelImportBoundaryTest`).
 */

// ---------------------------------------------------------------------------
// KeyedSetCell — per-key re-put atomicity `ORA2 §MODEL-08`
// ---------------------------------------------------------------------------

/**
 * `KeyedSetCell` at **key** granularity (`ORA2 §MODEL-08`; `[24-SET-01]`, `[24-SET-03]` lifted
 * to a key): the key -> element table, and the property that the table is what a downstream
 * fold sees *at every prefix of the script*, never a torn intermediate.
 *
 * ## Why a second keyed model beside [KeyedSetSourceModel]
 *
 * [KeyedSetSourceModel] folds the same slice and exposes the live **element set**, which is
 * what a `SetDelta` terminal downstream of the cell reads — the right observable for ORA1's
 * membership-level differential. It cannot state ORA2's property, because "exactly one live
 * element for this key" is invisible once the key is projected away: two keys binding the same
 * element collapse to one element, and a key binding two elements would be indistinguishable
 * from two keys binding one each. This model keeps the key, so the property is expressible.
 *
 * ## The property, and why the model cannot fail it by construction
 *
 * The kernel ships a re-put as `SetDelta(adds = {new -> freshTag}, dels = {old -> oldTag})` —
 * **one** delta, so no downstream fold ever observes the key with two live elements (the add
 * landed, the retract has not) or with none (the retract landed, the add has not). Here a key
 * is a single slot in a map: two-live and zero-live are unrepresentable, exactly as group death
 * is unrepresentable in [GroupByModel]. That is the modelled statement of atomicity — and
 * "unrepresentable" is a claim about *this* implementation that only a test keeps true, which
 * is why [bindingsAtEachPrefix] exists and `TaggedKeyedModelTest` walks every prefix rather
 * than only the final state.
 *
 * Two kernel behaviours the fold reproduces deliberately:
 *
 * - re-putting the **identical** element is a no-op in the kernel (effective-only, 21: no fresh
 *   tag, no delta). Assigning the same value to the slot is the same no-op here.
 * - removing a key the cell never held is a no-op, and leaves nothing behind.
 */
object KeyedReputModel : SourceModel, Serializable {

    /** The key -> element table after the slice's first [prefix] events. */
    fun bindingsAt(slice: SourceScript, prefix: Int): Map<Any?, Any?> {
        require(prefix in 0..slice.events.size) {
            "Prefix $prefix is outside '${slice.source.id}'s ${slice.events.size}-event log"
        }
        val bindings = LinkedHashMap<Any?, Any?>()
        slice.events.take(prefix).forEach { event ->
            when (event) {
                is ScriptEvent.Put -> bindings[event.key] = event.element
                is ScriptEvent.RemoveKey -> bindings.remove(event.key)
                else -> Unit
            }
        }
        return bindings
    }

    /**
     * The table at **every** prefix, from the empty one to the complete one — the instrument
     * `ORA2 §MODEL-08`'s "at every script prefix" clause is checked with. `size + 1` entries.
     */
    fun bindingsAtEachPrefix(slice: SourceScript): List<Map<Any?, Any?>> =
        (0..slice.events.size).map { bindingsAt(slice, it) }

    /** The key -> element table the slice leaves behind. */
    override fun evaluate(slice: SourceScript): ModelState = ModelState.MapState(bindingsAt(slice, slice.events.size))

    override fun toString(): String = "KeyedReputModel"
}

// ---------------------------------------------------------------------------
// MergeableGroupByCell — grow/merge-only `ORA2 §MODEL-09`
// ---------------------------------------------------------------------------

/**
 * `MergeableGroupByCell` — grouped aggregation that **grows and merges and never retracts**,
 * with that absence stated here as the modelled specification (`ORA2 §MODEL-09`,
 * `[24-OP-GROUPBY-01]`).
 *
 * ## The absence is the specification, not a gap
 *
 * The cell's own KDoc is explicit: *"unlike `GroupByCell` there is no `retract` — a merge cannot
 * be un-applied in general … element-level retraction on `inlet` belongs to the non-replicated
 * `GroupByCell`."* Its `onLocal` reads `delta.adds` and nothing else; a `SetDelta`'s `dels` are
 * dropped on the floor by design, because an accumulator that is only commutative and
 * associative has no inverse. So a script that adds `x` and later removes it leaves the
 * aggregate **unchanged**, and a reference model that shrank the aggregate would be reporting a
 * kernel bug where the kernel is doing precisely what it promises.
 *
 * This model therefore folds the slice's [ScriptEvent.Add] events **in order, one fold per add
 * event**, and ignores every removal. Per add rather than per distinct element, because that is
 * what the cell does: a re-add of a live element mints a fresh tag upstream and arrives as
 * another `adds` entry, which `fold` merges again. For an idempotent [merge] (max, min, set
 * union) that is invisible; for a counted one (`+`) it is the difference between a right and a
 * wrong answer, so it is not rounded off here.
 *
 * ## Its relation to ORA1's exclusion of the same cell
 *
 * ORA1 **excluded** `MergeableGroupByCell` from its vocabulary (see the ledger in
 * `MapCellModel.kt`), on the ground that `ORA1 §MODEL-06` demands a reference reproduce
 * aggregator *retraction* exactly, and this cell's only removal path is a peer's gossiped
 * `MapDelta.removals` — replication, which ORA1 does not model. Nothing about that reasoning is
 * withdrawn here: ORA2 does not model the gossiped-removal path either (`ORA2 §MODEL-09` asks
 * for grow/merge-only and says so). What changed is which requirement governs. ORA1 measured
 * the cell against `ORA1 §MODEL-06`'s retraction clause and found it unmodellable; ORA2 states
 * non-retraction as the specification and checks *that*, which is a strictly weaker and
 * honestly-labelled claim about a strictly smaller behaviour.
 *
 * That leaves the ORA1 ledger entry's prose narrower than the truth once this model exists.
 * Correcting it belongs to whichever ORA2 task edits `MapCellModel.kt` — it is outside this
 * task's file claim, and is recorded on the bead rather than silently reached for.
 *
 * ## Shape
 *
 * A [SourceModel] over the slice that feeds the cell, not an [OperatorModel] over a
 * [ModelState]: a grow-only aggregate is **not a function of live membership**. Two scripts with
 * identical live sets — one that only added, one that added more and removed the surplus —
 * produce different aggregates in the kernel, so an operator model reading a `SetState` could
 * not tell them apart and would have to guess. Making the dependency on the script explicit is
 * what keeps this model honest; the alternative silently assumes no removal ever happened.
 */
class MergeableGroupByModel(
    private val keyOf: ElementKey,
    private val accumulate: ElementAccumulator,
    private val merge: AccumulatorMerge,
) : SourceModel, Serializable {

    /** The aggregate table after the slice's first [prefix] events. */
    fun aggregatesAt(slice: SourceScript, prefix: Int): Map<Any?, Any?> {
        require(prefix in 0..slice.events.size) {
            "Prefix $prefix is outside '${slice.source.id}'s ${slice.events.size}-event log"
        }
        val groups = LinkedHashMap<Any?, Any?>()
        slice.events.take(prefix).forEach { event ->
            if (event !is ScriptEvent.Add) return@forEach
            val key = keyOf.keyOf(event.element)
            val partial = accumulate.accumulate(event.element)
            groups[key] = if (groups.containsKey(key)) merge.merge(groups[key], partial) else partial
        }
        return groups
    }

    /** The aggregate table at every prefix — the instrument the non-retraction test walks. */
    fun aggregatesAtEachPrefix(slice: SourceScript): List<Map<Any?, Any?>> =
        (0..slice.events.size).map { aggregatesAt(slice, it) }

    override fun evaluate(slice: SourceScript): ModelState =
        ModelState.MapState(aggregatesAt(slice, slice.events.size))

    override fun toString(): String = "MergeableGroupByModel($keyOf, $accumulate, $merge)"
}

/** An element's partial accumulator — `MergeableGroupByCell`'s `accumulate: (E) -> A`. */
fun interface ElementAccumulator : Serializable {
    fun accumulate(element: Any?): Any?
}

/**
 * The commutative-associative accumulator merge — `MergeableGroupByCell`'s `merge: (A, A) -> A`.
 *
 * Commutativity and associativity are the cell's own precondition (it is `Replicable`, and CP-F3
 * refuses a non-idempotent accumulator wired to a replicated sink at link time). Neither is
 * checked here: a model that policed its caller's function would be checking the harness, not
 * the kernel.
 */
fun interface AccumulatorMerge : Serializable {
    fun merge(left: Any?, right: Any?): Any?
}

// ---------------------------------------------------------------------------
// PnCounterCell — per-source cumulative totals, pointwise max `ORA2 §MODEL-10`
// ---------------------------------------------------------------------------

/**
 * `PnCounterCell` as a **convergent** reference (`ORA2 §MODEL-10`, `[42-REPL-04]`): per-source
 * cumulative increment and decrement totals, resolved across replicas by pointwise maximum.
 *
 * ## Why this is a different model from [PnCounterSourceModel], not a duplicate
 *
 * [PnCounterSourceModel] answers what **one instance** reads: increments minus decrements over
 * its own slice. That is all a single-stream batch differential can ask, and it is identical to
 * `CounterCell`'s answer — which is exactly why it cannot distinguish the two cells. The
 * distinguishing behaviour is convergence: `PnCounterDelta.merge` is pointwise **max** over
 * per-source cumulative totals, so a gossip echo is absorbed rather than added twice, and a
 * replica that has heard a peer's total twice reads the same number as one that heard it once.
 * Plain addition — `CounterDelta`'s merge — double-counts on a mesh. This model states the
 * pointwise-max lattice, so a replicated case can tell them apart.
 *
 * ## The source of a total is the *cell*, not the writer
 *
 * `PnCounterCell` credits `UUID.nameUUIDFromBytes("pn-source:${'$'}{ref.id}:${'$'}{ref.instanceId}")` —
 * one slot per **instance**. Two [WriterId]s incrementing one instance share its slot and their
 * amounts simply add into one cumulative total; two instances are two slots. So the model keys
 * its totals by [SourceId], the same reasoning [DotModel]'s KDoc sets out for dots.
 *
 * ## Amounts must be non-negative
 *
 * Pointwise max converges only over totals that **grow**. `PnCounterOps.increment(-5)` would
 * make a source's cumulative `incs` fall, and a merge that took the max would then silently
 * discard the newer, smaller total — the counter would appear to converge on a stale reading.
 * That is why the PN-counter has two grow-only halves in the first place: a decrease is a
 * `decrement`, credited to the `decs` half, never a negative increment. A negative amount is
 * refused here **by name** rather than folded into a plausible number.
 */
object PnCounterConvergenceModel : Serializable {

    /** Thrown when a slice carries a negative amount — see this object's KDoc. */
    class NonMonotonicAmountException(message: String) : IllegalStateException(message)

    /** [source]'s own cumulative totals over its slice. */
    fun fold(slice: SourceScript): PnCounterState {
        var incs = 0L
        var decs = 0L
        slice.events.forEach { event ->
            when (event) {
                is ScriptEvent.Increment -> incs += requireNonNegative(event.amount, "increment", slice)
                is ScriptEvent.Decrement -> decs += requireNonNegative(event.amount, "decrement", slice)
                else -> Unit
            }
        }
        if (incs == 0L && decs == 0L) return PnCounterState.EMPTY
        return PnCounterState(
            incs = if (incs == 0L) emptyMap() else mapOf(slice.source to incs),
            decs = if (decs == 0L) emptyMap() else mapOf(slice.source to decs),
        )
    }

    /** Every replica's own state, in slice order. */
    fun perInstance(script: Script): Map<SourceId, PnCounterState> =
        script.sources().associateWith { fold(script.slice(it)) }

    /**
     * The converged state: every replica's totals merged by pointwise max. Distinct sources
     * union; a source both sides hold resolves to the greater total, which is what makes a
     * duplicate delivery a no-op.
     */
    fun converged(script: Script): PnCounterState =
        perInstance(script).values.fold(PnCounterState.EMPTY) { acc, state -> acc.merge(state) }

    /** The converged reading — `sum(incs) - sum(decs)`, as a terminal observes it. */
    fun evaluate(script: Script): ModelState.ScalarState = ModelState.ScalarState(converged(script).total())

    private fun requireNonNegative(amount: Long, verb: String, slice: SourceScript): Long {
        if (amount < 0) {
            throw NonMonotonicAmountException(
                "PnCounterConvergenceModel refuses a negative $verb ($amount) in '${slice.source.id}': " +
                    "pointwise max converges only over per-source totals that grow, and a negative " +
                    "amount would let a merge discard the newer total. Use the other half of the " +
                    "counter instead.",
            )
        }
        return amount
    }

    override fun toString(): String = "PnCounterConvergenceModel"
}

/**
 * Per-source cumulative increment and decrement totals — the PN-counter lattice
 * (`ORA2 §MODEL-10`).
 *
 * Two independent grow-only halves, merged by pointwise max with identity `0L` (an
 * un-incremented source has contributed nothing). Commutative, associative and **idempotent**,
 * which is what terminates a mesh echo; `TaggedKeyedModelTest` proves all three.
 */
data class PnCounterState(
    val incs: Map<SourceId, Long> = emptyMap(),
    val decs: Map<SourceId, Long> = emptyMap(),
) : Serializable {

    /** Pointwise max over both halves. */
    fun merge(other: PnCounterState): PnCounterState =
        PnCounterState(mergeMax(incs, other.incs), mergeMax(decs, other.decs))

    /** The reading: everything added, less everything taken away. */
    fun total(): Long = incs.values.sum() - decs.values.sum()

    private fun mergeMax(left: Map<SourceId, Long>, right: Map<SourceId, Long>): Map<SourceId, Long> {
        if (right.isEmpty()) return left
        if (left.isEmpty()) return right
        val merged = LinkedHashMap(left)
        right.forEach { (source, total) -> merged[source] = maxOf(merged[source] ?: 0L, total) }
        return merged
    }

    companion object {
        val EMPTY: PnCounterState = PnCounterState()
    }
}

// ---------------------------------------------------------------------------
// OrMapCell — single-instance dot semantics, `ORA2 §MODEL-11`
// ---------------------------------------------------------------------------

/**
 * Adapts [DotModel] to [SourceModel]'s single-slice contract — the seam every other registered
 * source model implements, and the one [DotModel] itself was never built to (it needs a whole
 * multi-instance [Script]; see `civictech.oracle.bind.TaggedOperators`' file KDoc, where this
 * model is registered under the catalog id `orMap`, for the full reasoning of why only this
 * single-instance restriction is registered and not the cross-instance convergence check).
 *
 * Correct **only** for a slice with no gossip [Delivery] — which is every slice this adapter
 * can honestly answer from alone, since resolving a delivery needs the sender's own log and
 * this method receives none. A slice with deliveries fails loudly by name rather than silently
 * folding without them, the same idiom [MapCellSourceModel]'s `MultiWriterMapSliceException`
 * uses for `MapCell`'s own single-writer restriction.
 *
 * The [DotOrder] is synthesized fresh per call, ranking only [SourceScript.source] itself: with
 * exactly one instance in play there is no tie to break (two dots from the *same* source can
 * never share a counter), so the rank value is inconsequential and does not need to come from a
 * harness the way a genuine multi-instance case's does (`ORA2 §MODEL-12`).
 *
 * ## Why this file, not beside its registration
 *
 * `civictech.oracle.bind.TaggedOperators.registerAll()` is where this model is bound into
 * `OperatorCatalog`, and that file legitimately imports the concrete kernel cell `OrMapCell` to
 * build the [civictech.cell.graph.CellFactory] side of the registration — the wiring `bind/` is
 * for. This model itself must not: `ORA1 §MODEL-10`/`ORA2 §MODEL-11` forbid a `ReferenceOp`
 * from referencing a concrete data-cell class, and `civictech.oracle.model`'s own source set is
 * exactly what `ModelImportBoundaryTest` scans to enforce that. Declaring this model in
 * `civictech.oracle.bind` instead (as it originally was, computenet-4ru.1.2) put a `ReferenceOp`
 * outside that scan's reach — closed by computenet-n00e, which moved it here and additionally
 * widened `ModelImportBoundaryTest` to catch any future `ReferenceOp` declared outside this
 * package too, wherever it lands.
 */
object SingleInstanceOrMapModel : SourceModel, Serializable {
    override fun evaluate(slice: SourceScript): ModelState {
        require(slice.deliveries.isEmpty()) {
            "SingleInstanceOrMapModel (catalog id 'orMap') cannot honestly evaluate a slice " +
                "carrying gossip deliveries for '${slice.source.id}': DotModel's cross-instance " +
                "merge needs the peer instances' own event logs " +
                "(DotModel.Fold.applyDeliveries reads script.slice(delivery.from)), which a single " +
                "SourceScript does not carry. This registration checks ONE OrMapCell instance's own " +
                "dot semantics in isolation; the replicated-mesh differential is the sweep/runner " +
                "task's, driving DotModel.converged/stateOf directly over a multi-instance Script."
        }
        val order = DotOrder.ranked(listOf(slice.source))
        return DotModel(order).evaluate(Script(listOf(slice)))
    }

    override fun toString(): String = "SingleInstanceOrMapModel"
}

// ---------------------------------------------------------------------------
// The tagged -> untagged adapter — effective-only, presence-aware `ORA2 §MODEL-11`
// ---------------------------------------------------------------------------

/**
 * The **untag adapter** as a reference: a converged tagged map, restated in the plain
 * key -> value vocabulary, and the emission discipline that restatement implies
 * (`96 §E1.5`, `[KE1-18]`..`[KE1-21]`; registered under the catalog id `untag` by
 * `civictech.oracle.bind.TaggedOperators`).
 *
 * ## Written from the specification, not from the cell
 *
 * `ORA1 §MODEL-10`/`ORA2 §MODEL-11` forbid this package from naming a
 * `civictech.cell.data.op` type, so this model cannot mention the kernel adapter it is
 * checked against — and that prohibition is the *point*, not an obstacle worked around: a
 * reference derived by reading the implementation cannot fail where the implementation is
 * wrong, which is the only reason `:oracle` exists as a separate module at all. What is
 * modelled here is therefore the **specified** behaviour of an untag adapter, stated in this
 * package's own terms:
 *
 * > *A tagged map's observable is the key -> value table of its live dots ([DotModel.entries]).
 * > An adapter that republishes that table in an untagged vocabulary must publish, at every
 * > step, exactly the difference between the table it last published and the table now — no
 * > more (an unchanged table is not news) and no less (every change to the published table is
 * > news).*
 *
 * Everything below is that one sentence made computable. It is deliberately expressed as a
 * diff between two whole tables rather than as an incremental fold over the arriving
 * deltas: an incremental fold would be a second copy of the kernel's machinery, while a
 * recomputation from the upstream observable is an independent statement of what that
 * machinery must produce (epic computenet-4ru design D2).
 *
 * ## The four properties this shape makes true, by construction
 *
 * - **`[KE1-18]` one put per exposed-value change.** A key whose published value differs from
 *   its value now — including a key that has just appeared — contributes exactly one entry to
 *   [EffectiveChange.puts]. Once, because a key is one slot in a map.
 * - **`[KE1-19]` a removal only when the last live dot dies.** [DotModel.entries] holds a key
 *   iff it has a live dot, so a key leaves the table exactly when its last live dot does. A
 *   tombstone that leaves *some* live dot behind leaves the key in the table with the surviving
 *   value, which reaches [diff] as a value change — a put, never a removal.
 * - **`[KE1-20]` nothing at all for an ineffective step.** Two equal tables diff to an empty
 *   [EffectiveChange], which [emissions] reports as `null` — *no emission*, distinct from an
 *   emission that happens to be empty. An equal-value re-put is exactly this case: the tagged
 *   map always mints a fresh dot, so the upstream state changes, while the *table* it exposes
 *   does not.
 * - **`[KE1-21]` one change per step, so a re-put crosses whole.** [emissions] produces at most
 *   one [EffectiveChange] per upstream step, and a re-put's retract and add both land in the
 *   same step's table — so the key's old value is never observable as absent between them.
 *   Representing a step's emission as a single value is what makes the torn intermediate
 *   unrepresentable here, the same way two-live-elements is unrepresentable in [KeyedReputModel].
 *
 * ## Presence and value are compared separately, never value alone
 *
 * A key may legitimately expose a `null` value, so `table[key] == null` cannot distinguish
 * "absent" from "present, holding null" and a value-only comparison would silently drop the
 * appearance of a null-valued key. Every comparison below therefore asks `containsKey` first
 * and compares values second.
 *
 * ## Shape: an [OperatorModel], and why its batch answer is a restatement
 *
 * At quiescence an adapter that adds and drops nothing publishes the upstream table itself, so
 * [evaluate] answers its input's entries. That is not a vacuous claim: the differential run
 * computes the model side from the *script* (through [DotModel], independently of any kernel
 * cell) and the kernel side by folding what the real adapter actually emitted, so a kernel that
 * dropped a key, published a stale value, or failed to emit a removal disagrees with it. The
 * emission-level properties above are not visible at quiescence and are checked against the
 * real cell's delta stream by `TaggedKeyedModelTest`, which drives [emissions] over the same
 * script.
 */
object UntagModel : OperatorModel, Serializable {

    /** This model's catalog id, named in its own failures so a mis-wired graph says which entry it was. */
    const val CATALOG_ID: String = "untag"

    /**
     * One step's publication: the keys whose published value changed or appeared, and the keys
     * whose last live dot died. Never both for one key — a key either has a live dot now or
     * does not.
     */
    data class EffectiveChange(val puts: Map<Any?, Any?>, val removals: Set<Any?>) : Serializable {
        /** Whether this step publishes nothing — the `[KE1-20]` case [emissions] reports as `null`. */
        fun isEmpty(): Boolean = puts.isEmpty() && removals.isEmpty()
    }

    /**
     * The publication that carries [previous] to [next]: every key whose presence or value
     * moved, and nothing else.
     *
     * Both directions are computed over the union of the two key sets rather than over a
     * caller-supplied "touched" set. A touched set would be this model mirroring the kernel's
     * own optimization, and a kernel that computed it wrongly would then be compared against a
     * reference making the identical mistake.
     */
    fun diff(previous: Map<Any?, Any?>, next: Map<Any?, Any?>): EffectiveChange {
        val puts = LinkedHashMap<Any?, Any?>()
        val removals = LinkedHashSet<Any?>()
        next.forEach { (key, value) ->
            // presence first: a key that has just appeared is news even when its value is null.
            if (!previous.containsKey(key) || previous[key] != value) puts[key] = value
        }
        previous.keys.forEach { key -> if (!next.containsKey(key)) removals += key }
        return EffectiveChange(puts, removals)
    }

    /**
     * What an adapter publishes across [tables], one entry per step from `tables[0]` onwards —
     * `null` where that step publishes nothing.
     *
     * [tables] is the sequence of upstream observables, starting with the table before any step
     * (ordinarily empty). The result has `tables.size - 1` entries, so the correspondence
     * "one input step, at most one emission" is structural rather than asserted.
     */
    fun emissions(tables: List<Map<Any?, Any?>>): List<EffectiveChange?> {
        require(tables.isNotEmpty()) { "UntagModel.emissions needs at least the initial table" }
        return (1 until tables.size).map { step ->
            diff(tables[step - 1], tables[step]).takeUnless { it.isEmpty() }
        }
    }

    /**
     * [emissions] over the tables a single tagged source exposes as [slice] is applied event by
     * event — the whole instrument, from a script alone, executing no kernel cell.
     *
     * Restricted to a delivery-free slice for exactly [SingleInstanceOrMapModel]'s reason, and
     * refuses by name rather than folding without the peer logs it would need.
     */
    fun emissionsOverSlice(slice: SourceScript): List<EffectiveChange?> {
        require(slice.deliveries.isEmpty()) {
            "UntagModel (catalog id '$CATALOG_ID') cannot honestly derive the published tables for " +
                "'${slice.source.id}' from a slice carrying gossip deliveries: resolving one needs the " +
                "peer instances' own event logs, which a single SourceScript does not carry. See " +
                "SingleInstanceOrMapModel for the same restriction on the upstream it adapts."
        }
        val script = Script(listOf(slice))
        val dots = DotModel(DotOrder.ranked(listOf(slice.source)))
        val tables = (0..slice.events.size).map { prefix ->
            dots.entries(dots.stateOf(script, slice.source, prefix)).entries
        }
        return emissions(tables)
    }

    /**
     * The upstream table, restated. See this object's KDoc for why a restatement is the honest
     * batch claim for an adapter, and where the emission-level properties are checked instead.
     */
    override fun evaluate(inputs: List<ModelState>): ModelState {
        require(inputs.size == 1) {
            "UntagModel (catalog id '$CATALOG_ID') is unary: it takes the tagged map it adapts and " +
                "nothing else, got ${inputs.size} inputs"
        }
        val upstream = inputs.single().asMap("UntagModel (catalog id '$CATALOG_ID')")
        return ModelState.MapState(LinkedHashMap(upstream.entries))
    }

    override fun toString(): String = "UntagModel"
}
