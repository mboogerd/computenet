package civictech.oracle.model

import java.io.Serializable

/**
 * The batch mirrors of the kernel's `Aggregators` family — the value half of
 * `GroupByCell(keyFn, aggregator)` (`[24-AGG-01]`, `[24-OP-GROUPBY-04]`,
 * `[24-OP-GROUPBY-05]`).
 *
 * ## Why these can be modelled at all, and modelled as pure functions of a collection
 *
 * `[24-AGG-01]` is the licence: *"An `Aggregator`'s `value(acc)` SHALL depend only on which
 * elements are currently live, never on insertion/retraction order, so arrival-order
 * aggregates (first/last/scan) are excluded."* An aggregate is therefore, by requirement, a
 * function of a **set of live elements** — which is exactly an [AggregateFunction]. The
 * kernel's `Aggregator` is the same function expressed as `empty`/`insert`/`retract`/`value`
 * so it can be maintained incrementally; the model states the function directly and never
 * retracts anything.
 *
 * That independence is the whole point of the pairing (epic computenet-4ru design D2). The
 * kernel's hard cases are all *retraction* cases and none of them exists here:
 *
 * - **Non-invertible aggregates** (`minOf`/`maxOf`/`topK`/`collectToSet`) force the kernel to
 *   keep the group's full support multiset — value → multiplicity in a `TreeMap` — because
 *   distinct live elements can share a selected value and retracting one of them must not
 *   retract the value (`[24-OP-GROUPBY-04]`; bounded-memory top-k is rejected outright as
 *   unsound, since an evicted value can become top again). A recomputation over the live
 *   elements gets that for free: the equal-valued sibling is simply still in the collection.
 *   `GroupByAggregatorTest` pins the case anyway, because "free" is only true while the model
 *   really is a recomputation.
 * - **Self-inverting aggregates** (`count`/`sumOf`/`avgOf`) are arithmetic in the kernel, and
 *   the reason its sums are `Long` rather than `Double` — *"float sums are order-sensitive"* —
 *   is a constraint on the incremental side that the model inherits for a different reason: a
 *   modelled `Double` sum would not be structurally equal to the kernel's `Long` one.
 *
 * ## Widths are part of the contract
 *
 * [ModelState] equality is structural, so a modelled aggregate must have the **same runtime
 * type** as the kernel's, not merely the same number. The kernel's declared value types are
 * the authority: `count()` and `sumOf()` are `Aggregator<E, Long, _>`, `avgOf()` is `Double`,
 * `minOf`/`maxOf` are the selector's `V`, `topK` is `List<V>` and `collectToSet` is `Set<E>`.
 * `ScalarState(2) != ScalarState(2L)`, and the mismatch is invisible until a differential run
 * exists — the same trap `CountModel`'s KDoc records for `CounterDelta`.
 */
object Aggregates {

    /** Live-element count, as a `Long` — the kernel's `Aggregators.count()` value type. */
    fun count(): AggregateFunction = Count

    /** Sum of a `Long` selector (`Aggregators.sumOf`). */
    fun sumOf(selector: LongSelector): AggregateFunction = Sum(selector)

    /**
     * Mean of a `Long` selector, as a `Double` (`Aggregators.avgOf`).
     *
     * The kernel divides once at read time — `acc.sum.toDouble() / acc.n` over an exact `Long`
     * sum and count — so the model's single division over the same two exact quantities gives
     * the identical `Double`, bit for bit. A group is never empty (a group with no live
     * elements is absent, `[24-OP-GROUPBY-02]`), so the `0/0` the kernel would produce is
     * unreachable in both halves.
     */
    fun avgOf(selector: LongSelector): AggregateFunction = Avg(selector)

    /** Smallest selected value (`Aggregators.minOf`). */
    fun minOf(selector: ElementSelector): AggregateFunction = Extremum(selector, min = true)

    /** Largest selected value (`Aggregators.maxOf`). */
    fun maxOf(selector: ElementSelector): AggregateFunction = Extremum(selector, min = false)

    /**
     * The [k] largest selected values, descending, **duplicates included**
     * (`Aggregators.topK`).
     *
     * Duplicates are the reason this reads the support multiset rather than a set of values:
     * the kernel walks its `TreeMap` in descending order emitting each value as many times as
     * its multiplicity, so a group of three elements sharing one selected value yields that
     * value three times. Sorting the selected values of the live elements descending and
     * taking [k] is the same list. Fewer than [k] live elements yields all of them.
     */
    fun topK(k: Int, selector: ElementSelector): AggregateFunction = TopK(k, selector)

    /** The group's live elements as a set (`Aggregators.collectToSet`) — the elements, not a projection. */
    fun collectToSet(): AggregateFunction = Collect

    private object Count : AggregateFunction, Serializable {
        override fun aggregate(elements: Collection<Any?>): Any? = elements.size.toLong()
        override fun toString(): String = "count"
    }

    private data class Sum(val selector: LongSelector) : AggregateFunction, Serializable {
        override fun aggregate(elements: Collection<Any?>): Any? =
            elements.sumOf { selector.selectLong(it) }

        override fun toString(): String = "sumOf($selector)"
    }

    private data class Avg(val selector: LongSelector) : AggregateFunction, Serializable {
        override fun aggregate(elements: Collection<Any?>): Any? =
            elements.sumOf { selector.selectLong(it) }.toDouble() / elements.size

        override fun toString(): String = "avgOf($selector)"
    }

    private data class Extremum(val selector: ElementSelector, val min: Boolean) :
        AggregateFunction, Serializable {

        override fun aggregate(elements: Collection<Any?>): Any? {
            val selected = elements.map { selector.select(it) }
            return if (min) selected.minWith(SelectedOrder) else selected.maxWith(SelectedOrder)
        }

        override fun toString(): String = "${if (min) "minOf" else "maxOf"}($selector)"
    }

    private data class TopK(val k: Int, val selector: ElementSelector) : AggregateFunction, Serializable {
        override fun aggregate(elements: Collection<Any?>): Any? =
            elements.map { selector.select(it) }.sortedWith(SelectedOrder.reversed()).take(k)

        override fun toString(): String = "topK($k, $selector)"
    }

    private object Collect : AggregateFunction, Serializable {
        override fun aggregate(elements: Collection<Any?>): Any? = LinkedHashSet(elements)
        override fun toString(): String = "collectToSet"
    }

    /**
     * Natural order over selected values, with a named failure when a selector produces
     * something that cannot be ordered.
     *
     * The kernel bounds its non-invertible selectors as `V : Comparable<V>, V : Serializable`
     * and `[24-OP-GROUPBY-05]` requires them to be total orders with a deterministic
     * tie-break. The model's element domain is untyped throughout (see [ElementShape]), so the
     * bound cannot be expressed in the type system and is checked here instead — loudly,
     * naming the value, rather than by an unchecked cast whose `ClassCastException` would
     * surface from inside a fold.
     */
    private object SelectedOrder : Comparator<Any?>, Serializable {
        @Suppress("UNCHECKED_CAST")
        override fun compare(left: Any?, right: Any?): Int {
            val comparable = left as? Comparable<Any?>
                ?: error(
                    "A min/max/topK selector produced a non-comparable value ($left); " +
                        "[24-OP-GROUPBY-05] requires such selectors to be total orders, and the " +
                        "kernel bounds them as V : Comparable<V>.",
                )
            return comparable.compareTo(right)
        }
    }
}

/**
 * One aggregate, as a pure function of a group's live elements.
 *
 * [elements] is never empty: a group with no live elements is absent from the result map
 * (`[24-OP-GROUPBY-02]`), so an implementation never has to invent a value for one. That is
 * why there is no `empty()` here to mirror the kernel's — the kernel needs an identity to
 * start an incremental accumulator from, and the model has nothing to start.
 *
 * Implementations must be pure and [Serializable], like every other function a model is
 * configured with; and their value must have the **same runtime type** as the kernel
 * aggregator they mirror — see [Aggregates]' KDoc on widths.
 */
fun interface AggregateFunction : Serializable {
    fun aggregate(elements: Collection<Any?>): Any?
}

/** A pure, serializable projection from an element to the value an aggregate reads. */
fun interface ElementSelector : Serializable {
    fun select(element: Any?): Any?
}

/**
 * An [ElementSelector] whose projection is a `Long` — what the kernel's self-inverting
 * aggregators (`sumOf`, `avgOf`) require, and the width their values carry.
 *
 * Declared as a sub-interface rather than a separate carrier so one canonical selector object
 * can configure **both** halves of a registration: the kernel factory, which needs an
 * `(E) -> Long`, and a model aggregate that only needs an [ElementSelector]. Two carriers
 * would let the two halves drift apart on the very projection they are supposed to share.
 */
interface LongSelector : ElementSelector {
    fun selectLong(element: Any?): Long
    override fun select(element: Any?): Any? = selectLong(element)
}
