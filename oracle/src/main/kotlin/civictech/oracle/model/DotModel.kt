package civictech.oracle.model

import java.io.Serializable

/**
 * The tagged-map (OR-map) reference model — `ORA2 §MODEL-01`..`ORA2 §MODEL-06`, and
 * the one place in `civictech.oracle.model` that reads *modelled* dot order.
 *
 * ## What it computes, and from what
 *
 * A pure fold from a complete multi-instance [Script] to, per instance, the live dot state it
 * holds — hence its membership (`[24-TMAP-02]`) and its per-key value (`[24-TMAP-03]`). No
 * kernel cell is executed, no kernel delta is read, and **no kernel `Timestamp` is touched**:
 * the model mints its own [ModelDot]s, from the script's own event positions.
 *
 * ## The honesty cost, stated where it is paid (`ORA2 §HONEST-01`)
 *
 * ORA1's model is membership-only by rule (`ORA1 §MODEL-03`) precisely so that it cannot
 * agree with the kernel about a shared bug in the tag algebra. This model **cannot** be
 * membership-only: for an OR-set, membership is decidable from causality alone, but an
 * OR-map's *value* is decided by a total order over dots, and a reference that refuses to
 * name a dot cannot state which of two concurrent puts wins. So ORA2 relaxes the rule
 * deliberately (feature computenet-4ru.1 §7 risk 1, design D2 option (b)) and is **less
 * independent of the algebra it checks** than ORA1's model is. What compensates is not an
 * argument, it is the controls: `ORA2 §CTL-01` (the arrival-order fold must fail),
 * `ORA2 §CTL-02` (an inverted dot order must be caught), `ORA2 §CTL-03` (reset-remove
 * replaced by remove-all must be caught), `ORA2 §CTL-04` (missing gossip must be caught).
 * Those live with the sweep/runner task; a green model suite alone is not evidence.
 *
 * The independence that *is* retained is the one this file can keep on its own: the dots are
 * **minted here, from the script**, not read from the kernel, and observation advances **only**
 * from [Delivery] (`ORA2 §MODEL-06`). A model that recovered either from a live cell would be
 * a mirror of the implementation, and would converge with it onto any bug.
 *
 * ## A modelled writer is a source *cell*, not a `WriterId`
 *
 * The feature's §3.3 prose says "for each modelled writer `w`" and then pins what `w` has to
 * be: the model's identity for `w` must be order-isomorphic to *"the kernel's real `dotSource`
 * for the corresponding cell"*. The kernel derives that source per **instance** —
 * `UUID.nameUUIDFromBytes("or-map-tags:${'$'}{ref.id}:${'$'}{ref.instanceId}")`, and increments one
 * `dotCounter` per instance — so the causal actor of the dot algebra is an `OrMapCell`
 * instance, i.e. one [SourceScript], not a [WriterId]. Two [WriterId]s driving one instance
 * share its dot source and its counter and cannot be concurrent at all: the cell is a single
 * serialization point. Genuine concurrency is two *instances*, which is exactly the
 * "3-replica mesh" every one of the feature's behaviour specifications describes.
 *
 * So [ModelDot] is keyed by [SourceId], and [WriterId] plays no part in dot identity.
 * `ORA2 §MODEL-12`'s model half is met by [DotOrder]: an **ordered instance identity supplied
 * from outside**, never recovered from kernel state.
 *
 * ## The fold, precisely
 *
 * Per instance `s`, in its own event order, interleaved with its [SourceScript.deliveries]:
 *
 * - `put(k, v)`: mint `dot = (nth put of s, s)`; tombstone every dot `s` currently holds live
 *   at `k`; record `dot -> v`. (Kernel: `OrMapCell.inletHandler().put`, which ships the fresh
 *   dot and the tombstones in one delta.)
 * - `remove(k)`: tombstone exactly the dots `s` currently holds live at `k` — reset-remove,
 *   `[24-TMAP-04]`. A key with no live dot is a **no-op**, effective-only (21).
 * - a [Delivery] from `t` through `n` events: merge in the state `t` held after its own first
 *   `n` events. That is the only way `s` learns anything about `t`.
 *
 * The dot **counter** advances on puts only, and never on a delivery — mirroring the kernel,
 * where `applyRemote` folds a peer's dots in without touching `dotCounter`. That is what makes
 * a dot's counter a pure function of the script position that minted it, computable before any
 * delivery is resolved.
 *
 * ## Cost
 *
 * `stateAfter` is memoized per `(source, prefix)`, so a script with `d` deliveries costs
 * `O(events x d)` merges in the worst case rather than exponentially many. Generated cases are
 * hundreds of events; a transparently correct fold is worth more here than a fast one.
 */
class DotModel(private val order: DotOrder) : Serializable {

    /** Thrown when [Delivery] edges form a cycle, so no reachable state answers the script. */
    class CyclicDeliveryException(message: String) : IllegalStateException(message)

    /** The converged state of the whole [script] — every instance's state merged. */
    fun converged(script: Script): DotState =
        perInstance(script).values.fold(DotState.EMPTY) { acc, state -> acc.merge(state) }

    /** Every instance's own state at the end of the script, in slice order. */
    fun perInstance(script: Script): Map<SourceId, DotState> {
        // One fold for the whole script: instances share delivered prefixes, and the memo is
        // what keeps a wide mesh from re-folding the same prefix once per reader.
        val fold = Fold(script)
        return script.sources().associateWith { fold.stateAfter(it, script.slice(it).events.size) }
    }

    /** [source]'s own state after its whole log. */
    fun stateOf(script: Script, source: SourceId): DotState =
        Fold(script).stateAfter(source, script.slice(source).events.size)

    /** [source]'s own state after its first [prefix] events — the wave-prefix instrument. */
    fun stateOf(script: Script, source: SourceId, prefix: Int): DotState =
        Fold(script).stateAfter(source, prefix)

    /**
     * The converged key -> value table (`ORA2 §MODEL-01`): a key is present iff it has a live
     * dot, and its value is the live dot maximal under [DotOrder].
     */
    fun evaluate(script: Script): ModelState.MapState = entries(converged(script))

    /** `[24-TMAP-02]` add-wins presence: the keys of [state] with at least one live dot. */
    fun membership(state: DotState): Set<Any?> = state.membership()

    /**
     * `[24-TMAP-03]`: the value of [key]'s live dot maximal under `(counter, sourceId)` —
     * `null` when the key is absent. **No wall clock and no arrival order participates**: the
     * comparator reads a counter minted from a script position and a rank supplied by the
     * harness, and nothing else. This is the one place ORA2's relaxation of
     * `ORA1 §MODEL-03` is actually spent.
     */
    fun value(state: DotState, key: Any?): Any? {
        val live = state.liveDots(key)
        if (live.isEmpty()) return null
        // Rank every live dot's instance, not only the ones a comparison happens to reach: a key
        // with a single live dot would otherwise return a value without the order ever being
        // consulted, and a harness that forgot to supply the mapping would get a plausible answer
        // on every uncontended key and a named failure only on a contended one. ORA2 §MODEL-12's
        // fail-loud is worth nothing if it fires only where the case is already interesting.
        live.keys.forEach { order.rankOf(it.source) }
        return live.entries.maxWithOrNull(compareBy(order.comparator()) { it.key })?.value
    }

    /** [membership] and [value] together, as the [ModelState] a terminal is compared against. */
    fun entries(state: DotState): ModelState.MapState =
        ModelState.MapState(state.membership().associateWith { value(state, it) })

    override fun toString(): String = "DotModel($order)"

    /** One script's memoized fold. Short-lived and confined to one call, so [DotModel] stays pure. */
    private inner class Fold(private val script: Script) {

        private val memo = HashMap<Pair<SourceId, Int>, DotState>()
        private val inProgress = LinkedHashSet<Pair<SourceId, Int>>()

        fun stateAfter(source: SourceId, prefix: Int): DotState {
            val slice = script.slice(source)
            require(prefix in 0..slice.events.size) {
                "Prefix $prefix is outside '${source.id}'s ${slice.events.size}-event log"
            }
            val at = source to prefix
            memo[at]?.let { return it }
            if (!inProgress.add(at)) {
                throw CyclicDeliveryException(
                    "Cyclic gossip deliveries: ${inProgress.joinToString(" -> ") { "${it.first.id}@${it.second}" }}" +
                        " -> ${source.id}@$prefix. No reachable replica state answers this script.",
                )
            }
            try {
                var state = DotState.EMPTY
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
            } finally {
                inProgress.remove(at)
            }
        }

        /**
         * Deliveries stated at [position] are unordered among themselves; they are folded in a
         * deterministic order anyway, because a *deterministic* model is worth more than an
         * argument that merge's commutativity makes the order immaterial. `DotModelTest` asserts
         * the commutativity independently.
         */
        private fun applyDeliveries(slice: SourceScript, position: Int, state: DotState): DotState =
            slice.deliveries
                .filter { it.afterEvents == position }
                .sortedWith(compareBy({ it.from.id }, { it.throughEvents }))
                .fold(state) { acc, delivery -> acc.merge(stateAfter(delivery.from, delivery.throughEvents)) }

        private fun apply(event: ScriptEvent, source: SourceId, counter: Long, state: DotState): DotState =
            when (event) {
                is ScriptEvent.Put -> state.put(event.key, ModelDot(counter, source), event.element)
                is ScriptEvent.RemoveKey -> state.resetRemove(event.key)
                // Everything else is another cell family's vocabulary in a mixed script, and is
                // ignored here exactly as `Membership.live` ignores keyed events: the model that
                // owns a slice decides what it accepts.
                else -> state
            }
    }
}

/**
 * A dot: the model's own `(counter, source)` pair, minted by [DotModel] from a script position.
 *
 * Structurally the kernel's `Timestamp(sourceId, counter)` and deliberately **not** that type:
 * a model that imported `civictech.cell.Timestamp` would be reading the kernel's dot identity
 * rather than stating its own, and `ORA2 §MODEL-12` forbids exactly that. `ModelImportBoundaryTest`
 * enforces it rather than trusting this paragraph.
 *
 * [counter] is 1-based per [source] — the nth put that instance issues mints counter `n`,
 * matching `OrMapCell`'s `++dotCounter`. Equality is structural, which is what makes merge
 * idempotent: one put mints one dot, and re-merging a dot the state already holds adds nothing.
 */
data class ModelDot(val counter: Long, val source: SourceId) : Serializable {
    init {
        require(counter > 0) { "A dot counter is 1-based; got $counter for '${source.id}'" }
    }

    override fun toString(): String = "${source.id}#$counter"
}

/**
 * The harness-supplied order over instance identities — `ORA2 §MODEL-12`'s model half.
 *
 * `[24-TMAP-03]`'s `DOT_ORDER` breaks a counter tie by `sourceId`, so the model agrees with the
 * kernel about which of two same-counter dots wins **only** if its instance order matches the
 * kernel's. The kernel's is the natural order of `UUID.nameUUIDFromBytes("or-map-tags:<id>:<instanceId>")`,
 * and the model must not compute that: deriving it here would re-implement the very derivation
 * a differential run exists to check, and would read a kernel identity the model is forbidden.
 *
 * So the order arrives as **ranks**, from the harness, at case-construction time. Ranks rather
 * than the identities themselves for a reason worth stating: a `UUID`'s natural order compares
 * its two `Long` halves *signed*, which is not the lexicographic order of its `toString()`. A
 * model that accepted a textual identity and sorted it would be subtly, silently wrong on about
 * half of all pairs. An `Int` rank cannot be got wrong that way — it forces whoever builds the
 * case to sort the real sources with the kernel's own comparator and hand over the result.
 *
 * There is deliberately **no default order**. [rankOf] fails loudly on an unranked source rather
 * than inventing insertion order, so a harness that forgets to supply the mapping gets a named
 * failure instead of a plausible answer.
 *
 * ### Risk 2, decided: the derivation expectation is checked in the harness, and this type is
 * ### what makes that check unavoidable
 *
 * The feature left open (§9 risk 2) whether the case builder should assert the kernel's
 * `"or-map-tags:${'$'}{ref.id}:${'$'}{ref.instanceId}"` derivation at construction time, so a KE1
 * change to it fails loudly rather than silently mis-ordering every tie. **Decision: yes — a
 * checked-in expectation, in the case builder, owned by the harness task** (feature §3.3 assigns
 * the mapping there, and `ORA2 §MODEL-12` says so in as many words). It cannot honestly live in
 * this file: the model would have to name the derivation string and the kernel type to assert
 * anything about it, which is `ORA2 §MODEL-11`'s boundary, and an expectation pinned here with
 * nothing to compare it against would be assurance about a formula rather than about the kernel.
 *
 * What this file contributes to that decision is the half it *can* enforce: the fail-loud
 * [rankOf] above. Because there is no defaulting, a harness cannot silently skip supplying the
 * kernel-derived order — the ranks are either stated or the fold dies by name.
 */
data class DotOrder(private val ranks: Map<SourceId, Int>) : Serializable {

    init {
        val collisions = ranks.entries.groupBy { it.value }.filterValues { it.size > 1 }
        require(collisions.isEmpty()) {
            "Two instances cannot share a rank — the kernel's sourceIds are distinct and totally " +
                "ordered: " + collisions.mapValues { (_, e) -> e.map { it.key.id }.sorted() }
        }
    }

    /** [source]'s rank, or a named failure — never a guess. */
    fun rankOf(source: SourceId): Int =
        ranks[source] ?: error(
            "No dot-order rank for source '${source.id}' (ranked: ${ranks.keys.map { it.id }.sorted()}). " +
                "ORA2 §MODEL-12: the harness supplies the instance order at case-construction time; " +
                "the model does not derive it from kernel state.",
        )

    /** `[24-TMAP-03]`'s total order over [ModelDot]s: counter first, then instance rank. */
    fun comparator(): Comparator<ModelDot> = compareBy<ModelDot> { it.counter }.thenBy { rankOf(it.source) }

    override fun toString(): String = "DotOrder(${ranks.entries.sortedBy { it.value }.joinToString { it.key.id }})"

    companion object {
        /**
         * Ranks [sources] in the order given — the caller **states** the order, having derived it
         * from the kernel's own comparator over the real instances' dot sources.
         *
         * Model-level unit tests use it to state an order directly, which is legitimate there:
         * there is no kernel instance to be isomorphic to, and the test is asserting what the
         * model does with an order rather than that the order is the right one.
         */
        fun ranked(sources: List<SourceId>): DotOrder {
            require(sources.distinct().size == sources.size) {
                "Duplicate source in a dot order: ${sources.map { it.id }}"
            }
            return DotOrder(sources.withIndex().associate { (rank, source) -> source to rank })
        }

        /** [ranked] over a vararg list. */
        fun ranked(vararg sources: SourceId): DotOrder = ranked(sources.toList())
    }
}

/**
 * One instance's dot state: every dot it has ever held, and the ones a remove tombstoned.
 *
 * The same two-halved shape as the kernel's `TaggedMapDelta` (`puts`, `dels`) and for the same
 * reason: keeping tombstones rather than deleting covered dots is what makes [merge] idempotent
 * and order-independent — a tombstone that arrives *before* the put it covers still covers it.
 * Dropping the covered dot instead would let a late-arriving put resurrect a removed key.
 *
 * `ORA2 §MODEL-02`: [merge] is pointwise union of both halves, and is therefore commutative,
 * associative and idempotent. `DotModelTest` proves all three rather than asserting them.
 */
data class DotState(
    /** Every dot ever recorded at a key, live or covered: `key -> (dot -> the value it put)`. */
    val puts: Map<Any?, Map<ModelDot, Any?>> = emptyMap(),
    /** The dots a reset-remove covered: `key -> tombstoned dots`. */
    val dels: Map<Any?, Set<ModelDot>> = emptyMap(),
) : Serializable {

    /** The dots at [key] no tombstone covers. */
    fun liveDots(key: Any?): Map<ModelDot, Any?> {
        val dots = puts[key] ?: return emptyMap()
        val covered = dels[key] ?: return dots
        return if (covered.isEmpty()) dots else dots.filterKeys { it !in covered }
    }

    /** `[24-TMAP-02]`: the keys with at least one live dot. */
    fun membership(): Set<Any?> = puts.keys.filterTo(LinkedHashSet()) { liveDots(it).isNotEmpty() }

    /** `ORA2 §MODEL-02` — pointwise union of the live and tombstoned halves. */
    fun merge(other: DotState): DotState {
        if (other.puts.isEmpty() && other.dels.isEmpty()) return this
        if (puts.isEmpty() && dels.isEmpty()) return other
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
        val mergedDels = LinkedHashMap<Any?, Set<ModelDot>>()
        (dels.keys + other.dels.keys).forEach { key ->
            val left = dels[key]
            val right = other.dels[key]
            mergedDels[key] = when {
                left == null -> right!!
                right == null -> left
                else -> LinkedHashSet(left).also { it.addAll(right) }
            }
        }
        return DotState(mergedPuts, mergedDels)
    }

    /**
     * `put(key, value)` minting [dot]: the fresh dot goes live and **everything this state
     * currently holds live at [key] is tombstoned in the same step** — the kernel's atomic
     * retract-then-add lifted to dots (`OrMapCell.put`).
     */
    fun put(key: Any?, dot: ModelDot, value: Any?): DotState {
        val observed = liveDots(key).keys
        val nextPuts = LinkedHashMap(puts)
        nextPuts[key] = LinkedHashMap(puts[key] ?: emptyMap()).also { it[dot] = value }
        val nextDels = if (observed.isEmpty()) dels else LinkedHashMap(dels).also {
            it[key] = LinkedHashSet(dels[key] ?: emptySet()).apply { addAll(observed) }
        }
        return DotState(nextPuts, nextDels)
    }

    /**
     * `[24-TMAP-04]` reset-remove: tombstone **exactly** the dots this state observes live at
     * [key], and nothing else. A dot this state has not seen — a concurrent put at another
     * instance — is simply not in the tombstone set and survives the merge, which is add-wins.
     *
     * A key with no live dot is a no-op, returning `this` unchanged (effective-only, 21). The
     * mutant this method exists to be distinguishable from is *remove-all* — tombstoning the
     * key's dots at the converged state instead of at this instance's — and `ORA2 §CTL-03`
     * requires the suite to catch that substitution.
     */
    fun resetRemove(key: Any?): DotState {
        val observed = liveDots(key).keys
        if (observed.isEmpty()) return this
        val nextDels = LinkedHashMap(dels)
        nextDels[key] = LinkedHashSet(dels[key] ?: emptySet()).apply { addAll(observed) }
        return DotState(puts, nextDels)
    }

    companion object {
        val EMPTY: DotState = DotState()
    }
}
