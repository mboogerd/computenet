package civictech.oracle.bind

import civictech.cell.data.Aggregators
import civictech.cell.data.CounterCell
import civictech.cell.data.KeyedSetCell
import civictech.cell.data.PnCounterCell
import civictech.cell.data.SetCell
import civictech.cell.data.op.CombineLatestCell
import civictech.cell.data.op.CountCell
import civictech.cell.data.op.FilterCell
import civictech.cell.data.op.FlatMapSetCell
import civictech.cell.data.op.GroupByCell
import civictech.cell.data.op.IntersectSetCell
import civictech.cell.data.op.JoinCell
import civictech.cell.data.op.JoinSetCell
import civictech.cell.data.op.LookupJoinCell
import civictech.cell.data.op.PresenceCountCell
import civictech.cell.data.op.QuorumSetCell
import civictech.cell.data.op.SemiJoinCell
import civictech.cell.data.op.UnionSetCell
import civictech.cell.graph.CellFactory
import civictech.oracle.model.AggregateFunction
import civictech.oracle.model.Aggregates
import civictech.oracle.model.CombineLatestModel
import civictech.oracle.model.CountModel
import civictech.oracle.model.CounterSourceModel
import civictech.oracle.model.ElementCombiner
import civictech.oracle.model.ElementExpansion
import civictech.oracle.model.ElementKey
import civictech.oracle.model.ElementPredicate
import civictech.oracle.model.ElementShape
import civictech.oracle.model.FilterModel
import civictech.oracle.model.FlatMapSetModel
import civictech.oracle.model.GroupByModel
import civictech.oracle.model.IntersectSetModel
import civictech.oracle.model.JoinModel
import civictech.oracle.model.JoinSetModel
import civictech.oracle.model.KeyedCombiner
import civictech.oracle.model.KeyedSetSourceModel
import civictech.oracle.model.LongSelector
import civictech.oracle.model.LookupJoinModel
import civictech.oracle.model.PnCounterSourceModel
import civictech.oracle.model.PresenceCountModel
import civictech.oracle.model.QuorumSetModel
import civictech.oracle.model.QuorumThreshold
import civictech.oracle.model.SemiJoinModel
import civictech.oracle.model.SetSourceModel
import civictech.oracle.model.UnionSetModel
import java.io.Serializable
import java.util.TreeMap

/**
 * The paired registrations for `[ORA1-MODEL-02]`'s vocabulary — the set-source, unary and
 * fan-in slice (computenet-4ru.5.1) and the binary, keyed-join, map-join and group-by family
 * (computenet-4ru.5.2): for each id, the kernel `CellFactory` that builds the real cell, the
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
     * later task extending the vocabulary (computenet-4ru.5.3) and the completeness test that
     * enumerates it cannot drift apart on a typo.
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

        // --- the binary/keyed/map/group-by family (computenet-4ru.5.2) ------

        const val INTERSECT = "intersect"
        const val JOIN_SET = "joinSet"
        const val SEMI_JOIN = "semiJoin"
        const val ANTI_JOIN = "antiJoin"
        const val JOIN = "join"
        const val COMBINE_LATEST = "combineLatest"
        const val LOOKUP_JOIN = "lookupJoin"
        const val GROUP_BY_COUNT = "groupByCount"
        const val GROUP_BY_SUM = "groupBySum"
        const val GROUP_BY_AVG = "groupByAvg"
        const val GROUP_BY_MIN = "groupByMin"
        const val GROUP_BY_MAX = "groupByMax"
        const val GROUP_BY_TOP_K = "groupByTopK"
        const val GROUP_BY_COLLECT_TO_SET = "groupByCollectToSet"
        const val GROUP_BY_GLOBAL = "groupByGlobal"

        /**
         * Every aggregate `GroupByCell` is registered over — `[ORA1-MODEL-02]` names the seven
         * `Aggregators` families explicitly, so the coverage claim is a list a test can read
         * rather than a promise in prose.
         */
        val GROUP_BY_AGGREGATES: List<String> = listOf(
            GROUP_BY_COUNT, GROUP_BY_SUM, GROUP_BY_AVG, GROUP_BY_MIN,
            GROUP_BY_MAX, GROUP_BY_TOP_K, GROUP_BY_COLLECT_TO_SET,
        )

        /** Every id this file registers, in registration order. */
        val ALL: List<String> = listOf(
            SET, KEYED_SET, COUNTER, PN_COUNTER,
            FILTER, FLAT_MAP_SET, MAP_SET, COUNT,
            UNION, PRESENCE_COUNT, QUORUM_SET,
            INTERSECT, JOIN_SET, SEMI_JOIN, ANTI_JOIN,
            JOIN, COMBINE_LATEST, LOOKUP_JOIN,
        ) + GROUP_BY_AGGREGATES + listOf(GROUP_BY_GLOBAL)
    }

    private val SCALAR = ElementShape.Scalar
    private val SCALAR_SET = ElementShape.SetOf(ElementShape.Scalar)

    /**
     * The canonical element domain of the keyed family: a `(key, value)` pair.
     *
     * The set-family entries registered above are `SetOf(Scalar)`, which carries no key to join
     * or group on. Rather than teach those entries about keys, the keyed family advertises a
     * pair-shaped stream and projects with [Keys.FIRST] / [Selectors.SECOND_AS_LONG] — the same
     * two-column shape `concord`'s `PAIR_SET` regime uses, and the shape a generator reaches
     * for when it needs a joinable stream.
     */
    private val PAIR = ElementShape.Tuple(2)
    private val PAIR_SET = ElementShape.SetOf(PAIR)

    /** `MapOf(Scalar, Scalar)` — the map-shaped edge the `JoinCell` family consumes. */
    private val SCALAR_MAP = ElementShape.MapOf(ElementShape.Scalar, ElementShape.Scalar)

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

        // --- binary set operators ------------------------------------------

        /* `IntersectSetCell` ([24-OP-INTERSECT-01]) — the identity join: the elements live on
         * both sides. The requirement's tag discipline (advertise on entry, delete every
         * advertised tag on exit, absorb membership-neutral churn) has no model counterpart
         * ([ORA1-MODEL-03]); its observable consequence is set intersection. */
        OperatorCatalog.register(
            id = Ids.INTERSECT,
            shape = ShapeRule.binary(SCALAR_SET, SCALAR_SET, SCALAR_SET),
            kernel = CellFactory { ref -> IntersectSetCell<Any?>(ref) },
            model = IntersectSetModel,
        )

        /* `JoinSetCell` — the relational equi-join ([24-OP-JOINSET-01], [24-OP-JOINSET-02]),
         * matching on the pair's first component and combining the two second components.
         *
         * The canonical combine is **many-to-one on purpose**: left `(k1, v)` and `(k2, v)`
         * joined against right `(k1, w)` and `(k2, w)` both produce `(v, w)`, which is exactly
         * the case [24-OP-JOINSET-02] governs — the kernel keeps one minted tag per
         * contributing pair so the output survives until the last pair dies, and the model
         * gets that from set union. A combine that could never collide would register the
         * operator without ever exercising the requirement's operative clause. */
        OperatorCatalog.register(
            id = Ids.JOIN_SET,
            shape = ShapeRule.binary(PAIR_SET, PAIR_SET, PAIR_SET),
            kernel = CellFactory { ref ->
                JoinSetCell<Any?, Any?, Any?, Any?>(
                    ref,
                    leftKey = { element -> Keys.FIRST.keyOf(element) },
                    rightKey = { element -> Keys.FIRST.keyOf(element) },
                    combine = { left, right -> Combiners.VALUES.combine(left, right) },
                )
            },
            model = JoinSetModel(Keys.FIRST, Keys.FIRST, Combiners.VALUES),
        )

        /* `SemiJoinCell` in both polarities ([24-OP-SEMIJOIN-01]) — [ORA1-MODEL-02] names
         * semijoin and antijoin separately while the kernel is one cell with a `negated` flag,
         * so they are two entries over one class.
         *
         * `emitOnFrontier` ([24-OP-SEMIJOIN-04]) is deliberately left at its default and has
         * no model counterpart: the gate changes WHEN the cell emits — coalescing a wave to
         * its net enter/exit set so a transient enter-then-exit is never observed — not what is
         * observable once both inputs are idle, which is the only thing a batch reference
         * states ([24-OP-SEMIJOIN-03]). A gated and an ungated cell agree at quiescence. */
        OperatorCatalog.register(
            id = Ids.SEMI_JOIN,
            shape = ShapeRule.binary(PAIR_SET, PAIR_SET, PAIR_SET),
            kernel = CellFactory { ref ->
                SemiJoinCell<Any?, Any?, Any?>(
                    ref,
                    leftKey = { element -> Keys.FIRST.keyOf(element) },
                    rightKey = { element -> Keys.FIRST.keyOf(element) },
                    negated = false,
                )
            },
            model = SemiJoinModel(Keys.FIRST, Keys.FIRST, negated = false),
        )

        /* The antijoin (`A ▷ B`) — the non-monotone polarity, and BS-5's operator: a right-side
         * arrival RETRACTS a live left element. */
        OperatorCatalog.register(
            id = Ids.ANTI_JOIN,
            shape = ShapeRule.binary(PAIR_SET, PAIR_SET, PAIR_SET),
            kernel = CellFactory { ref ->
                SemiJoinCell<Any?, Any?, Any?>(
                    ref,
                    leftKey = { element -> Keys.FIRST.keyOf(element) },
                    rightKey = { element -> Keys.FIRST.keyOf(element) },
                    negated = true,
                )
            },
            model = SemiJoinModel(Keys.FIRST, Keys.FIRST, negated = true),
        )

        // --- map-shaped joins ----------------------------------------------

        /*
         * The three map-shaped joins. All three consume `MapDelta` streams, which carry no
         * causal tags (G-23), so concurrent same-key puts resolve by **arrival order** —
         * [24-OP-JOIN-01] states that of `JoinCell` in the requirement itself, and the other
         * two inherit it from the same delta type.
         *
         * `[ORA1-MODEL-08]` requires that constraint to be documented at the registration
         * site, so: **these entries are defined for single-writer-per-key map inputs.** The
         * restriction is not on the operators — given two settled map states each is a pure,
         * deterministic function of them, which is what the models compute — but on whatever
         * produces the map stream. Every map-shaped producer registered here today
         * (`presenceCount`, the `groupBy*` family) is a single writer of its output by
         * construction, so the constraint is currently vacuous; it stops being vacuous when
         * `MapCell` joins the vocabulary (computenet-4ru.5.3), which is where its
         * single-writer-FIFO restriction is enforced.
         */

        /* `JoinCell` ([24-OP-JOIN-01]) — the keyed inner join; the pair of latest values for
         * every key both sides hold. */
        OperatorCatalog.register(
            id = Ids.JOIN,
            shape = ShapeRule.binary(SCALAR_MAP, SCALAR_MAP, ElementShape.MapOf(SCALAR, PAIR)),
            kernel = CellFactory { ref -> JoinCell<Any?, Any?, Any?>(ref) },
            model = JoinModel,
        )

        /* `CombineLatestCell` — the keyed OUTER combine. No `[24-OP-*]` id covers this cell;
         * its KDoc is the contract, and the canonical combine below is total, so every key
         * either side holds appears in the output, null-extended on the absent side. A
         * `combine` returning null would drop the key — that filtering half of the contract is
         * exercised by `JoinModelTest` rather than by this entry, which stays a plain outer
         * combine. `emitOnFrontier` is left at its default for the reason the semijoin entries
         * give. */
        OperatorCatalog.register(
            id = Ids.COMBINE_LATEST,
            shape = ShapeRule.binary(SCALAR_MAP, SCALAR_MAP, SCALAR_MAP),
            kernel = CellFactory { ref ->
                CombineLatestCell<Any?, Any?, Any?, Any?>(ref) { key, left, right ->
                    Combiners.OUTER_TEXT.combine(key, left, right)
                }
            },
            model = CombineLatestModel(Combiners.OUTER_TEXT),
        )

        /* `LookupJoinCell` — the foreign-key/dimension left-outer join, keyed by the FACT key.
         * The canonical foreign key is the fact key's leading character, a genuinely
         * many-to-one projection, so one dimension row serves several facts and a dimension
         * change fans out — the cell's reactive-on-both-sides behaviour. No `[24-OP-*]` id
         * covers this cell either; see `LookupJoinModel`'s KDoc for why `[24-OP-LOOKUP-01]`,
         * which this task's bead cites, is a concord scenario id for a different cell. */
        OperatorCatalog.register(
            id = Ids.LOOKUP_JOIN,
            shape = ShapeRule.binary(SCALAR_MAP, SCALAR_MAP, SCALAR_MAP),
            kernel = CellFactory { ref ->
                LookupJoinCell<Any?, Any?, Any?, Any?, Any?>(
                    ref,
                    fk = { key -> Keys.LEADING_CHARACTER.keyOf(key) },
                    combine = { key, value, dimension -> Combiners.ENRICH_TEXT.combine(key, value, dimension) },
                )
            },
            model = LookupJoinModel(Keys.LEADING_CHARACTER, Combiners.ENRICH_TEXT),
        )

        // --- grouped aggregation -------------------------------------------

        /*
         * `GroupByCell` over each of the seven `Aggregators` families ([ORA1-MODEL-02] names
         * them individually, so each is its own entry). All seven group on the pair's first
         * component; the five value-projecting ones select the second component as a `Long`.
         *
         * Every one of them carries [24-OP-GROUPBY-02]'s group-death rule: when a group's last
         * member is retracted the key is ABSENT from the output, never present with a stale or
         * identity value. The model gets that structurally — see GroupByModel's KDoc.
         *
         * The output VALUE shapes are worth reading: `[ElementShape]` has no ordered-list
         * variant, so `groupByTopK`'s `List<Long>` is advertised as an opaque `Scalar` — no
         * registered operator consumes a list-shaped value, and advertising a decomposable
         * shape would claim a linkability that does not exist. `groupByCollectToSet`'s value
         * genuinely is a set of the group's elements, so it is advertised as one.
         */

        OperatorCatalog.register(
            id = Ids.GROUP_BY_COUNT,
            shape = ShapeRule.unary(PAIR_SET, SCALAR_MAP),
            kernel = CellFactory { ref ->
                GroupByCell<Any?, Any?, Long, Long>(
                    ref,
                    keyFn = { element -> Keys.FIRST.keyOf(element) },
                    aggregator = Aggregators.count(),
                )
            },
            model = GroupByModel(Keys.FIRST, Aggregates.count()),
        )

        OperatorCatalog.register(
            id = Ids.GROUP_BY_SUM,
            shape = ShapeRule.unary(PAIR_SET, SCALAR_MAP),
            kernel = CellFactory { ref ->
                GroupByCell<Any?, Any?, Long, Long>(
                    ref,
                    keyFn = { element -> Keys.FIRST.keyOf(element) },
                    aggregator = Aggregators.sumOf { element -> Selectors.SECOND_AS_LONG.selectLong(element) },
                )
            },
            model = GroupByModel(Keys.FIRST, Aggregates.sumOf(Selectors.SECOND_AS_LONG)),
        )

        OperatorCatalog.register(
            id = Ids.GROUP_BY_AVG,
            shape = ShapeRule.unary(PAIR_SET, SCALAR_MAP),
            kernel = CellFactory { ref ->
                GroupByCell<Any?, Any?, Double, Aggregators.SumCount>(
                    ref,
                    keyFn = { element -> Keys.FIRST.keyOf(element) },
                    aggregator = Aggregators.avgOf { element -> Selectors.SECOND_AS_LONG.selectLong(element) },
                )
            },
            model = GroupByModel(Keys.FIRST, Aggregates.avgOf(Selectors.SECOND_AS_LONG)),
        )

        OperatorCatalog.register(
            id = Ids.GROUP_BY_MIN,
            shape = ShapeRule.unary(PAIR_SET, SCALAR_MAP),
            kernel = CellFactory { ref ->
                GroupByCell<Any?, Any?, Long, TreeMap<Long, Int>>(
                    ref,
                    keyFn = { element -> Keys.FIRST.keyOf(element) },
                    aggregator = Aggregators.minOf { element -> Selectors.SECOND_AS_LONG.selectLong(element) },
                )
            },
            model = GroupByModel(Keys.FIRST, Aggregates.minOf(Selectors.SECOND_AS_LONG)),
        )

        OperatorCatalog.register(
            id = Ids.GROUP_BY_MAX,
            shape = ShapeRule.unary(PAIR_SET, SCALAR_MAP),
            kernel = CellFactory { ref ->
                GroupByCell<Any?, Any?, Long, TreeMap<Long, Int>>(
                    ref,
                    keyFn = { element -> Keys.FIRST.keyOf(element) },
                    aggregator = Aggregators.maxOf { element -> Selectors.SECOND_AS_LONG.selectLong(element) },
                )
            },
            model = GroupByModel(Keys.FIRST, Aggregates.maxOf(Selectors.SECOND_AS_LONG)),
        )

        OperatorCatalog.register(
            id = Ids.GROUP_BY_TOP_K,
            shape = ShapeRule.unary(PAIR_SET, SCALAR_MAP),
            kernel = CellFactory { ref ->
                GroupByCell<Any?, Any?, List<Long>, TreeMap<Long, Int>>(
                    ref,
                    keyFn = { element -> Keys.FIRST.keyOf(element) },
                    aggregator = Aggregators.topK(CANONICAL_TOP_K) { element ->
                        Selectors.SECOND_AS_LONG.selectLong(element)
                    },
                )
            },
            model = GroupByModel(Keys.FIRST, Aggregates.topK(CANONICAL_TOP_K, Selectors.SECOND_AS_LONG)),
        )

        /* `collectToSet` collects the group's ELEMENTS, not a projection of them, so its value
         * shape is the input's element shape. The kernel bounds the family as
         * `E : Serializable`, which is why this factory's element type is `Serializable`
         * rather than the `Any?` its siblings use. */
        OperatorCatalog.register(
            id = Ids.GROUP_BY_COLLECT_TO_SET,
            shape = ShapeRule.unary(PAIR_SET, ElementShape.MapOf(SCALAR, PAIR_SET)),
            kernel = CellFactory { ref ->
                GroupByCell<Serializable, Any?, Set<Serializable>, HashSet<Serializable>>(
                    ref,
                    keyFn = { element -> Keys.FIRST.keyOf(element) },
                    aggregator = Aggregators.collectToSet(),
                )
            },
            model = GroupByModel(Keys.FIRST, Aggregates.collectToSet()),
        )

        /* `GroupByCell.global` ([24-OP-GROUPBY-01]) — fold-to-scalar as ONE constant-key group.
         * The outlet stays a `MapDelta<String, A>` under the key `"global"`, so the model is a
         * one-entry map, not a scalar; an empty input yields an EMPTY map, because the
         * constant-key group dies with its last element like any other. */
        OperatorCatalog.register(
            id = Ids.GROUP_BY_GLOBAL,
            shape = ShapeRule.unary(PAIR_SET, SCALAR_MAP),
            kernel = CellFactory { ref -> GroupByCell.global(Aggregators.count<Any?>(), ref) },
            model = GroupByModel.global(Aggregates.count()),
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

    /** How many values `groupByTopK` keeps — small enough that a canonical case can exceed it. */
    private const val CANONICAL_TOP_K = 2

    /**
     * Canonical key projections — `JoinSetCell`/`SemiJoinCell`'s `leftKey`/`rightKey`,
     * `GroupByCell`'s `keyFn`, and `LookupJoinCell`'s foreign key `fk`.
     *
     * **Every projection here is total over `Any?`**, and that is a deliberate property rather
     * than defensive coding. The element domain of a generated case is the generator's choice
     * (`[ORA1-GEN-04]`), not the registration's: a projection that threw on an unexpected
     * domain would make *the registration* the thing that fails a sweep, rather than the
     * operator the sweep was built to exercise. Both halves of an entry take the same object,
     * so kernel and model degrade identically on an unexpected element — which is the only
     * property that matters for a differential comparison.
     */
    object Keys {
        /** The first component of a pair; a non-pair element is its own key. */
        val FIRST: ElementKey = object : ElementKey {
            override fun keyOf(element: Any?): Any? = (element as? Pair<*, *>)?.first ?: element
            override fun toString(): String = "first"
        }

        /**
         * The leading character of the key's text, as a single-character `String` — the
         * canonical foreign key of `lookupJoin`. Many-to-one on purpose: several fact keys
         * share one dimension row, so a dimension change fans out to all of them, which is the
         * cell's reactive-on-both-sides behaviour. An empty text projects to the empty string.
         */
        val LEADING_CHARACTER: ElementKey = object : ElementKey {
            override fun keyOf(element: Any?): Any? = element.toString().take(1)
            override fun toString(): String = "leadingCharacter"
        }
    }

    /** Canonical value selectors for the aggregate family. */
    object Selectors {
        /**
         * The pair's second component as a `Long` — the width `Aggregators.sumOf`/`avgOf`
         * require and the width `minOf`/`maxOf`/`topK` then carry into their values.
         *
         * Total over `Any?` like [Keys]: a `Number` is narrowed, and anything else falls back
         * to its text length, so the projection is defined on every element domain a generator
         * might pick and the two halves fall back identically.
         */
        val SECOND_AS_LONG: LongSelector = object : LongSelector {
            override fun selectLong(element: Any?): Long {
                val selected = (element as? Pair<*, *>)?.second ?: element
                return (selected as? Number)?.toLong() ?: selected.toString().length.toLong()
            }

            override fun toString(): String = "secondAsLong"
        }
    }

    /** Canonical combinations for the join family. */
    object Combiners {
        /**
         * `JoinSetCell`'s combine: the two rows' values, dropping the join key.
         *
         * Many-to-one by construction — two left rows under different keys carrying the same
         * value collapse onto one output element — which is what makes the entry exercise
         * `[24-OP-JOINSET-02]`'s per-pair tag collapse rather than merely instantiate the cell.
         */
        val VALUES: ElementCombiner = object : ElementCombiner {
            override fun combine(left: Any?, right: Any?): Any? =
                ((left as? Pair<*, *>)?.second ?: left) to ((right as? Pair<*, *>)?.second ?: right)

            override fun toString(): String = "values"
        }

        /**
         * `CombineLatestCell`'s combine: both sides' latest values as text, with the absent
         * side reading `null` — the null-extension that makes the entry an *outer* combine.
         * Total (it never returns null), so this entry never drops a key; the drop half of the
         * contract is exercised by `JoinModelTest` with its own combiner.
         */
        val OUTER_TEXT: KeyedCombiner = object : KeyedCombiner {
            override fun combine(key: Any?, left: Any?, right: Any?): Any? = "$left|$right"
            override fun toString(): String = "outerText"
        }

        /**
         * `LookupJoinCell`'s combine: the fact value enriched with its dimension row, the
         * dimension reading `null` when the fact references a row the dimension table does not
         * hold — the left-outer half of that cell's contract.
         */
        val ENRICH_TEXT: KeyedCombiner = object : KeyedCombiner {
            override fun combine(key: Any?, left: Any?, right: Any?): Any? = "$left@$right"
            override fun toString(): String = "enrichText"
        }
    }
}
