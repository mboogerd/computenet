package civictech.oracle.bind

import civictech.cell.data.CounterCell
import civictech.cell.data.KeyedSetCell
import civictech.cell.data.PnCounterCell
import civictech.cell.data.SetCell
import civictech.cell.data.op.CountCell
import civictech.cell.data.op.FilterCell
import civictech.cell.data.op.FlatMapSetCell
import civictech.cell.data.op.PresenceCountCell
import civictech.cell.data.op.QuorumSetCell
import civictech.cell.data.op.UnionSetCell
import civictech.cell.graph.CellFactory
import civictech.oracle.model.CountModel
import civictech.oracle.model.CounterSourceModel
import civictech.oracle.model.ElementExpansion
import civictech.oracle.model.ElementPredicate
import civictech.oracle.model.ElementShape
import civictech.oracle.model.FilterModel
import civictech.oracle.model.FlatMapSetModel
import civictech.oracle.model.KeyedSetSourceModel
import civictech.oracle.model.PnCounterSourceModel
import civictech.oracle.model.PresenceCountModel
import civictech.oracle.model.QuorumSetModel
import civictech.oracle.model.QuorumThreshold
import civictech.oracle.model.SetSourceModel
import civictech.oracle.model.UnionSetModel

/**
 * The paired registrations for the set-source, unary and fan-in slice of `[ORA1-MODEL-02]`'s
 * vocabulary: for each id, the kernel `CellFactory` that builds the real cell, the
 * [civictech.oracle.model.ReferenceOp] that says what the answer should be, and the
 * [ShapeRule] the generator links graphs by.
 *
 * ## Why this file is in `bind` and not `model`
 *
 * It imports `civictech.cell.data.op` types by necessity — it is the half of the seam that
 * *names* kernel cells. `[ORA1-MODEL-10]` forbids exactly that in `civictech.oracle.model`,
 * and `ModelImportBoundaryTest` enforces it there; keeping the kernel-facing half here is
 * what lets the model stay an independent reference while the catalog still pairs the two
 * (`[ORA1-API-02]`).
 *
 * ## Serializability
 *
 * A recorded case crosses a JVM boundary, so both halves must be serializable, and a Kotlin
 * lambda is only as serializable as what it captures. Every factory below captures
 * **nothing**: its configuration is a named `object` in [Predicates], [Expansions] or
 * [Thresholds], referenced statically. The models take the same objects, through the
 * `Serializable` function interfaces `civictech.oracle.model` declares for the purpose. A raw
 * `(E) -> Boolean` captured in either half would be a non-serializable
 * `kotlin.jvm.functions.Function1`, and the failure would surface only the first time a case
 * was written to disk — far from the registration that caused it.
 * `CoreOperatorsTest` pins this by round-tripping every registered factory through Java
 * serialization.
 *
 * ## One canonical configuration per id
 *
 * `filter` is registered with one predicate, `quorumSet` with one threshold, and so on. That
 * is enough for this feature: the vocabulary's *coverage* is what `[ORA1-MODEL-02]` asks
 * for, while configuration *variety* is the generator's business (computenet-4ru.6), which
 * reads a rule and picks its own instances.
 *
 * ## Registration is explicit, and process-wide
 *
 * [registerAll] is a call, not a static initializer, because [OperatorCatalog] is a mutable
 * singleton with a [OperatorCatalog.reset] that tests use. A file whose registrations
 * happened at class-load time would be silently emptied by the first test that reset the
 * catalog and never come back.
 */
object CoreOperators {

    /**
     * The catalog ids this file binds. Named constants rather than string literals so a
     * later task extending the vocabulary (computenet-4ru.5.2, 4ru.5.3) and the completeness
     * test that enumerates it cannot drift apart on a typo.
     */
    object Ids {
        const val SET = "set"
        const val KEYED_SET = "keyedSet"
        const val COUNTER = "counter"
        const val PN_COUNTER = "pnCounter"
        const val FILTER = "filter"
        const val FLAT_MAP_SET = "flatMapSet"
        const val MAP_SET = "mapSet"
        const val UNION = "union"
        const val COUNT = "count"
        const val PRESENCE_COUNT = "presenceCount"
        const val QUORUM_SET = "quorumSet"

        /** Every id this file registers, in registration order. */
        val ALL: List<String> = listOf(
            SET, KEYED_SET, COUNTER, PN_COUNTER,
            FILTER, FLAT_MAP_SET, MAP_SET, COUNT,
            UNION, PRESENCE_COUNT, QUORUM_SET,
        )
    }

    private val SCALAR = ElementShape.Scalar
    private val SCALAR_SET = ElementShape.SetOf(ElementShape.Scalar)

    /**
     * The canonical fan-in arity the fan-in entries advertise.
     *
     * `UnionSetCell`, `PresenceCountCell` and `QuorumSetCell` are n-ary in the kernel (a
     * dynamic link fan-in) and n-ary in the model (any `inputs` length). A [ShapeRule],
     * though, is a fixed ordered list, because that is what makes the generator's
     * linkability question answerable uniformly (`[ORA1-API-03]`). Two arms is therefore the
     * *advertised* arity, not a limit of either half; a generator that wants a three-arm
     * quorum registers a second rule rather than teaching itself about fan-in.
     */
    private const val CANONICAL_FAN_IN_ARITY = 2

    private val FAN_IN_SET_INPUTS = List(CANONICAL_FAN_IN_ARITY) { SCALAR_SET }

    /**
     * Binds every id in [Ids.ALL] into [OperatorCatalog].
     *
     * @throws IllegalStateException if any of them is already registered — [OperatorCatalog]
     *   refuses silent rebinding, so a test that registers must
     *   [reset][OperatorCatalog.reset] afterwards.
     */
    fun registerAll() {
        // --- sources -------------------------------------------------------

        /* `SetCell` — the OR-set source. The model is observed-remove membership over the
         * source's script slice ([24-SET-01], [24-SET-03]); see Membership's KDoc. */
        OperatorCatalog.register(
            id = Ids.SET,
            shape = ShapeRule.source(SCALAR_SET),
            kernel = CellFactory { ref -> SetCell<Any?>(ref) },
            model = SetSourceModel,
        )

        /* `KeyedSetCell` — keyed upsert whose outlet is a `SetDelta<E>`, so the modelled
         * observable is the live ELEMENT set, not the key table. See
         * KeyedSetSourceModel's KDoc for the recorded divergence from the bead's prose. */
        OperatorCatalog.register(
            id = Ids.KEYED_SET,
            shape = ShapeRule.source(SCALAR_SET),
            kernel = CellFactory { ref -> KeyedSetCell<Any?, Any?>(ref) },
            model = KeyedSetSourceModel,
        )

        /* `CounterCell` — net total. Merge is addition: commutative, NOT idempotent, so the
         * cell is single-instance and never replicated ([24-OP-COUNTER-01]). A generated
         * case must not replicate it; that constraint belongs to the generator. */
        OperatorCatalog.register(
            id = Ids.COUNTER,
            shape = ShapeRule.source(SCALAR),
            kernel = CellFactory { ref -> CounterCell(ref) },
            model = CounterSourceModel,
        )

        /* `PnCounterCell` — the replicable counter ([24-OP-PNCOUNTER-01]). Same batch value
         * as `counter` and deliberately its own entry: they differ in convergence class,
         * which a replicated case distinguishes and a batch fold cannot. */
        OperatorCatalog.register(
            id = Ids.PN_COUNTER,
            shape = ShapeRule.source(SCALAR),
            kernel = CellFactory { ref -> PnCounterCell(ref) },
            model = PnCounterSourceModel,
        )

        // --- unary operators -----------------------------------------------

        /* `FilterCell` ([24-OP-FILTER-01]). Both halves take the SAME predicate object, so
         * the kernel cell and its reference cannot drift. */
        OperatorCatalog.register(
            id = Ids.FILTER,
            shape = ShapeRule.unary(SCALAR_SET, SCALAR_SET),
            kernel = CellFactory { ref -> FilterCell<Any?>(ref) { element -> Predicates.TEXT_LENGTH_IS_EVEN.test(element) } },
            model = FilterModel(Predicates.TEXT_LENGTH_IS_EVEN),
        )

        /* `FlatMapSetCell` ([24-OP-FLATMAP-01], [24-OP-FLATMAP-02]). The canonical expansion
         * is many-to-one on purpose: distinct characters of distinct elements collide, which
         * is the case where the kernel needs preimage tag-set union to keep an output live
         * and the model needs nothing but set union. A one-to-one expansion would register
         * the operator without ever exercising the requirement's operative clause. */
        OperatorCatalog.register(
            id = Ids.FLAT_MAP_SET,
            shape = ShapeRule.unary(SCALAR_SET, SCALAR_SET),
            kernel = CellFactory { ref -> FlatMapSetCell<Any?, Any?>(ref) { element -> Expansions.TEXT_CHARACTERS.expand(element) } },
            model = FlatMapSetModel(Expansions.TEXT_CHARACTERS),
        )

        /* `mapSet` — `FlatMapSetCell(f = { listOf(f(it)) })` in the kernel
         * (FlatMapSetCell.kt:88). Registered separately because [ORA1-MODEL-02] names it
         * separately, with a singleton-image expansion. */
        OperatorCatalog.register(
            id = Ids.MAP_SET,
            shape = ShapeRule.unary(SCALAR_SET, SCALAR_SET),
            kernel = CellFactory { ref -> FlatMapSetCell<Any?, Any?>(ref) { element -> Expansions.TO_TEXT.expand(element) } },
            model = FlatMapSetModel(Expansions.TO_TEXT),
        )

        /* `CountCell` — distinct live elements as a scalar ([24-OP-COUNT-01]). */
        OperatorCatalog.register(
            id = Ids.COUNT,
            shape = ShapeRule.unary(SCALAR_SET, SCALAR),
            kernel = CellFactory { ref -> CountCell<Any?>(ref) },
            model = CountModel,
        )

        // --- fan-in operators ----------------------------------------------

        /* `UnionSetCell` ([24-OP-UNION-01]). Advertised at CANONICAL_FAN_IN_ARITY; both
         * halves are n-ary. */
        OperatorCatalog.register(
            id = Ids.UNION,
            shape = ShapeRule(FAN_IN_SET_INPUTS, SCALAR_SET),
            kernel = CellFactory { ref -> UnionSetCell<Any?>(ref) },
            model = UnionSetModel,
        )

        /* `PresenceCountCell` — element → number of distinct arms asserting it; an element
         * asserted by no arm is absent from the map, matching the cell's `removal(e)` on a
         * drop to zero. */
        OperatorCatalog.register(
            id = Ids.PRESENCE_COUNT,
            shape = ShapeRule(FAN_IN_SET_INPUTS, ElementShape.MapOf(SCALAR, SCALAR)),
            kernel = CellFactory { ref -> PresenceCountCell<Any?>(ref) },
            model = PresenceCountModel,
        )

        /* `QuorumSetCell` at the canonical majority threshold (`n / 2 + 1`), the same lambda
         * `QuorumSetCell.majority` uses. The model's `n` is the graph's static arm count: a
         * mid-run link open/close, and the `[24-REPLAY-01]` baseline bypass, are not
         * expressible in a script and are therefore outside what this reference defines —
         * see QuorumSetModel's KDoc. */
        OperatorCatalog.register(
            id = Ids.QUORUM_SET,
            shape = ShapeRule(FAN_IN_SET_INPUTS, SCALAR_SET),
            kernel = CellFactory { ref -> QuorumSetCell<Any?>(ref) { arms -> Thresholds.MAJORITY.required(arms) } },
            model = QuorumSetModel(Thresholds.MAJORITY),
        )
    }

    // -----------------------------------------------------------------------
    // Static configuration tables. Named objects, referenced statically by both
    // halves of every entry, so no factory and no model captures anything that
    // is not serializable.
    // -----------------------------------------------------------------------

    /** Canonical predicates. */
    object Predicates {
        /**
         * Total over `Any?` and independent of the element domain the generator picks:
         * `element.toString().length` is even. Arbitrary by design — a filter's *semantics*
         * are "keep what passes", and any pure total predicate exercises them.
         */
        val TEXT_LENGTH_IS_EVEN: ElementPredicate = object : ElementPredicate {
            override fun test(element: Any?): Boolean = element.toString().length % 2 == 0
            override fun toString(): String = "textLengthIsEven"
        }
    }

    /** Canonical expansions. */
    object Expansions {
        /** Many-to-one: the distinct characters of `element.toString()`, as single-character strings. */
        val TEXT_CHARACTERS: ElementExpansion = object : ElementExpansion {
            override fun expand(element: Any?): Iterable<Any?> =
                element.toString().map { it.toString() }.distinct()

            override fun toString(): String = "textCharacters"
        }

        /** One-to-one: `element.toString()` — the `mapSet` shape. */
        val TO_TEXT: ElementExpansion = object : ElementExpansion {
            override fun expand(element: Any?): Iterable<Any?> = listOf(element.toString())
            override fun toString(): String = "toText"
        }
    }

    /** Canonical quorum thresholds. */
    object Thresholds {
        /** A strict majority of arms: `n / 2 + 1` — the same expression `QuorumSetCell.majority` uses. */
        val MAJORITY: QuorumThreshold = object : QuorumThreshold {
            override fun required(arms: Int): Int = arms / 2 + 1
            override fun toString(): String = "majority"
        }
    }
}
