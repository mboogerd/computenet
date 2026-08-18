package civictech.oracle.bind

import civictech.cell.CellRef
import civictech.cell.graph.CellFactory
import civictech.oracle.model.ElementShape
import civictech.oracle.model.ModelState
import civictech.oracle.model.OperatorModel
import civictech.oracle.model.ScriptEvent
import civictech.oracle.model.SourceModel
import civictech.oracle.model.SourceScript
import civictech.oracle.model.SourceId
import civictech.oracle.model.WriterId
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream
import java.util.UUID

/**
 * [CoreOperators] as the production half of the paired-registration seam (`[ORA1-API-02]`):
 * every id in this task's slice of `[ORA1-MODEL-02]` is in [OperatorCatalog] as a **full**
 * entry — kernel factory, reference model, and a [ShapeRule] the generator can read
 * (`[ORA1-API-03]`).
 *
 * [OperatorCatalog] is a process-wide mutable singleton, so this class registers per test and
 * resets afterwards, exactly as `OperatorCatalogTest` does.
 */
class CoreOperatorsTest {

    @BeforeEach
    fun register() {
        OperatorCatalog.reset()
        CoreOperators.registerAll()
    }

    @AfterEach
    fun emptyTheProcessWideCatalog() {
        OperatorCatalog.reset()
    }

    @Test
    fun `every id in this slice of the vocabulary is registered, in declared order`() {
        OperatorCatalog.ids().toList() shouldContainExactly CoreOperators.Ids.ALL
    }

    /**
     * The pairing itself. `Entry` cannot exist half-bound by construction, so what this
     * checks beyond registration succeeding is that each entry's model is actually an
     * *evaluable* one — a `ReferenceOp` that is neither a source nor an operator would
     * satisfy the catalog and be useless to [civictech.oracle.model.ReferenceModel].
     */
    @Test
    fun `every entry carries a kernel factory, an evaluable model, and a shape rule`() {
        CoreOperators.Ids.ALL.forEach { id ->
            val entry = OperatorCatalog.entry(id).shouldNotBeNull()
            withClue("$id: kernel factory") { entry.kernel.shouldBeInstanceOf<CellFactory>() }
            withClue("$id: the model must be evaluable, not a bare ReferenceOp") {
                (entry.model is SourceModel || entry.model is OperatorModel) shouldBe true
            }
            withClue("$id: a source has zero input ports, an operator at least one") {
                (entry.shape.arity == 0) shouldBe (entry.model is SourceModel)
            }
        }
    }

    @Test
    fun `the source entries advertise the shapes their folds produce`() {
        OperatorCatalog.shapeOf(CoreOperators.Ids.SET) shouldBe
            ShapeRule.source(ElementShape.SetOf(ElementShape.Scalar))
        OperatorCatalog.shapeOf(CoreOperators.Ids.KEYED_SET) shouldBe
            ShapeRule.source(ElementShape.SetOf(ElementShape.Scalar))
        OperatorCatalog.shapeOf(CoreOperators.Ids.COUNTER) shouldBe ShapeRule.source(ElementShape.Scalar)
        OperatorCatalog.shapeOf(CoreOperators.Ids.PN_COUNTER) shouldBe ShapeRule.source(ElementShape.Scalar)
    }

    @Test
    fun `count narrows a set to a scalar and presenceCount widens a fan-in to a map`() {
        OperatorCatalog.shapeOf(CoreOperators.Ids.COUNT) shouldBe
            ShapeRule.unary(ElementShape.SetOf(ElementShape.Scalar), ElementShape.Scalar)

        val presence = OperatorCatalog.shapeOf(CoreOperators.Ids.PRESENCE_COUNT).shouldNotBeNull()
        presence.inputs shouldContainExactly listOf(
            ElementShape.SetOf(ElementShape.Scalar),
            ElementShape.SetOf(ElementShape.Scalar),
        )
        presence.output shouldBe ElementShape.MapOf(ElementShape.Scalar, ElementShape.Scalar)
    }

    /**
     * The fan-in family advertises a fixed canonical arity while both halves are n-ary —
     * see `CoreOperators.CANONICAL_FAN_IN_ARITY`'s KDoc for why a [ShapeRule] cannot express
     * "any arity" and why that is not a limit on either half.
     */
    @Test
    fun `the fan-in entries advertise a canonical arity while their models stay n-ary`() {
        listOf(CoreOperators.Ids.UNION, CoreOperators.Ids.PRESENCE_COUNT, CoreOperators.Ids.QUORUM_SET)
            .forEach { id -> withClue(id) { OperatorCatalog.shapeOf(id)!!.arity shouldBe 2 } }

        val union = OperatorCatalog.entry(CoreOperators.Ids.UNION)!!.model as OperatorModel
        val threeArms = List(3) { arm -> ModelState.SetState(setOf("shared", "arm$arm")) }
        union.evaluate(threeArms) shouldBe ModelState.SetState(setOf("shared", "arm0", "arm1", "arm2"))
    }

    /**
     * The registered kernel factory and the registered model are configured with the **same**
     * predicate object, so the two halves of an entry cannot drift. Checked observably: the
     * canonical filter keeps even-length text, so `"ab"` passes and `"abc"` does not.
     */
    @Test
    fun `filter's model is configured with the same predicate the kernel factory uses`() {
        val filter = OperatorCatalog.entry(CoreOperators.Ids.FILTER)!!.model as OperatorModel

        filter.evaluate(listOf(ModelState.SetState(setOf("ab", "abc", "abcd")))) shouldBe
            ModelState.SetState(setOf("ab", "abcd"))
    }

    @Test
    fun `flatMapSet expands to characters and mapSet to a single text image`() {
        val flatMap = OperatorCatalog.entry(CoreOperators.Ids.FLAT_MAP_SET)!!.model as OperatorModel
        val mapSet = OperatorCatalog.entry(CoreOperators.Ids.MAP_SET)!!.model as OperatorModel
        val input = listOf(ModelState.SetState(setOf("ab", "bc")))

        withClue("'b' is produced by both preimages and appears once") {
            flatMap.evaluate(input) shouldBe ModelState.SetState(setOf("a", "b", "c"))
        }
        mapSet.evaluate(input) shouldBe ModelState.SetState(setOf("ab", "bc"))
    }

    @Test
    fun `quorumSet is registered at the majority threshold`() {
        val quorum = OperatorCatalog.entry(CoreOperators.Ids.QUORUM_SET)!!.model as OperatorModel
        val arms = listOf(
            ModelState.SetState(setOf("both", "left")),
            ModelState.SetState(setOf("both", "right")),
            ModelState.SetState(setOf("both", "left")),
        )

        withClue("majority of 3 arms is 2: 'left' has 2, 'right' has 1") {
            quorum.evaluate(arms) shouldBe ModelState.SetState(setOf("both", "left"))
        }
    }

    @Test
    fun `the set source model registered under set is observed-remove membership`() {
        val set = OperatorCatalog.entry(CoreOperators.Ids.SET)!!.model as SourceModel
        val a = WriterId("A")
        val b = WriterId("B")

        val live = set.evaluate(
            SourceScript(SourceId("s"), listOf(ScriptEvent.Add(a, "x"), ScriptEvent.Remove(b, "x"))),
        )

        live shouldBe ModelState.SetState(setOf("x"))
    }

    /**
     * A recorded case crosses a JVM boundary, and a `CellFactory` is `Serializable` precisely
     * so a `GraphSpec` can be graphs-as-data. A factory that captured a raw Kotlin lambda —
     * a non-serializable `kotlin.jvm.functions.Function1` — would satisfy the type and fail
     * the first time a case was written out, far from the registration that caused it. Both
     * halves are pinned: the factories here, and the models through the `Serializable`
     * function interfaces `civictech.oracle.model` declares.
     */
    @Test
    fun `every registered kernel factory and model survives Java serialization`() {
        CoreOperators.Ids.ALL.forEach { id ->
            val entry = OperatorCatalog.entry(id)!!
            withClue("$id: kernel factory is not serializable") { serialize(entry.kernel) }
            withClue("$id: reference model is not serializable") { serialize(entry.model) }
        }
    }

    /**
     * Registration binds, it never constructs — but the factories must be real ones, not the
     * throwing stand-in `OperatorCatalogTest` uses. Building each cell once proves the
     * bindings name constructible kernel cells.
     */
    @Test
    fun `every registered kernel factory builds a real cell`() {
        CoreOperators.Ids.ALL.forEach { id ->
            val cell = OperatorCatalog.entry(id)!!.kernel.create(CellRef(UUID.randomUUID()))
            withClue("$id builds no cell") { cell.shouldNotBeNull() }
        }
    }

    // -- the binary / keyed / map / group-by family (computenet-4ru.5.2) ----

    /**
     * `[ORA1-MODEL-02]` names the vocabulary operator by operator, so its coverage claim is
     * checked id by id rather than by a count. The seven `Aggregators` families are
     * `GROUP_BY_AGGREGATES`; semijoin and antijoin are separate entries because the
     * requirement names both polarities.
     */
    @Test
    fun `the binary, keyed-join, map-join and group-by family is registered id by id`() {
        val registered = OperatorCatalog.ids()

        listOf(
            CoreOperators.Ids.INTERSECT,
            CoreOperators.Ids.JOIN_SET,
            CoreOperators.Ids.SEMI_JOIN,
            CoreOperators.Ids.ANTI_JOIN,
            CoreOperators.Ids.JOIN,
            CoreOperators.Ids.COMBINE_LATEST,
            CoreOperators.Ids.LOOKUP_JOIN,
            CoreOperators.Ids.GROUP_BY_GLOBAL,
        ).plus(CoreOperators.Ids.GROUP_BY_AGGREGATES)
            .forEach { id -> withClue(id) { (id in registered) shouldBe true } }

        withClue("[ORA1-MODEL-02] names all seven Aggregators families") {
            CoreOperators.Ids.GROUP_BY_AGGREGATES.size shouldBe 7
        }
    }

    @Test
    fun `the join family advertises binary shapes and the group-by family unary set-to-map ones`() {
        val pairSet = ElementShape.SetOf(ElementShape.Tuple(2))
        val scalarMap = ElementShape.MapOf(ElementShape.Scalar, ElementShape.Scalar)

        OperatorCatalog.shapeOf(CoreOperators.Ids.INTERSECT) shouldBe ShapeRule.binary(
            ElementShape.SetOf(ElementShape.Scalar),
            ElementShape.SetOf(ElementShape.Scalar),
            ElementShape.SetOf(ElementShape.Scalar),
        )
        OperatorCatalog.shapeOf(CoreOperators.Ids.JOIN_SET) shouldBe
            ShapeRule.binary(pairSet, pairSet, pairSet)
        withClue("both semijoin polarities emit LEFT rows, so their output shape is the left input's") {
            listOf(CoreOperators.Ids.SEMI_JOIN, CoreOperators.Ids.ANTI_JOIN).forEach { id ->
                OperatorCatalog.shapeOf(id) shouldBe ShapeRule.binary(pairSet, pairSet, pairSet)
            }
        }
        OperatorCatalog.shapeOf(CoreOperators.Ids.JOIN) shouldBe ShapeRule.binary(
            scalarMap,
            scalarMap,
            ElementShape.MapOf(ElementShape.Scalar, ElementShape.Tuple(2)),
        )
        CoreOperators.Ids.GROUP_BY_AGGREGATES.forEach { id ->
            withClue("$id consumes a pair-shaped set and emits a map") {
                OperatorCatalog.shapeOf(id)!!.inputs shouldContainExactly listOf(pairSet)
                OperatorCatalog.shapeOf(id)!!.output.shouldBeInstanceOf<ElementShape.MapOf>()
            }
        }
    }

    /**
     * The registered halves are configured with the **same** key, selector and combiner
     * objects, so kernel and model cannot drift. Checked observably on the model half, since
     * that is the half a differential run compares against: `joinSet` matches on the pair's
     * first component and emits the two values, and its many-to-one collapse is real.
     */
    @Test
    fun `joinSet's model matches on the pair key and collapses colliding combinations`() {
        val joinSet = OperatorCatalog.entry(CoreOperators.Ids.JOIN_SET)!!.model as OperatorModel

        val matched = joinSet.evaluate(
            listOf(
                ModelState.SetState(setOf("k1" to "v", "k2" to "v", "unmatched" to "u")),
                ModelState.SetState(setOf("k1" to "w", "k2" to "w")),
            ),
        )

        withClue("two pairs collapse onto one combination, and the unmatched row contributes none") {
            matched shouldBe ModelState.SetState(setOf("v" to "w"))
        }
    }

    /**
     * The two polarities are genuinely different entries — an antijoin registered as a
     * semijoin would satisfy every structural check above.
     */
    @Test
    fun `semiJoin and antiJoin are registered at opposite polarities`() {
        val inputs = listOf(
            ModelState.SetState(setOf("k1" to "a", "k2" to "b")),
            ModelState.SetState(setOf("k1" to "x")),
        )

        val semiJoin = OperatorCatalog.entry(CoreOperators.Ids.SEMI_JOIN)!!.model as OperatorModel
        val antiJoin = OperatorCatalog.entry(CoreOperators.Ids.ANTI_JOIN)!!.model as OperatorModel

        semiJoin.evaluate(inputs) shouldBe ModelState.SetState(setOf("k1" to "a"))
        antiJoin.evaluate(inputs) shouldBe ModelState.SetState(setOf("k2" to "b"))
    }

    /**
     * `combineLatest` is registered as an **outer** combine — a key only one side holds still
     * produces output, null-extended — and `lookupJoin` as a left-outer FK join whose output is
     * keyed by the fact key.
     */
    @Test
    fun `combineLatest null-extends and lookupJoin enriches by the fact key`() {
        val combineLatest = OperatorCatalog.entry(CoreOperators.Ids.COMBINE_LATEST)!!.model as OperatorModel
        val lookupJoin = OperatorCatalog.entry(CoreOperators.Ids.LOOKUP_JOIN)!!.model as OperatorModel

        combineLatest.evaluate(
            listOf(ModelState.MapState(mapOf("k" to "v")), ModelState.MapState(mapOf("j" to "w"))),
        ) shouldBe ModelState.MapState(mapOf("k" to "v|null", "j" to "null|w"))

        withClue("the canonical fk is the fact key's leading character, so 'a1' reads dimension 'a'") {
            lookupJoin.evaluate(
                listOf(
                    ModelState.MapState(mapOf("a1" to "fact", "z9" to "orphan")),
                    ModelState.MapState(mapOf("a" to "dim")),
                ),
            ) shouldBe ModelState.MapState(mapOf("a1" to "fact@dim", "z9" to "orphan@null"))
        }
    }

    /**
     * The widths `[24-AGG-01]`'s families carry, read off the registered models: `count` and
     * `sumOf` are `Long`, `avgOf` is `Double`, `minOf`/`maxOf` carry the selector's type (a
     * `Long` here, since the canonical selector is `secondAsLong`), `topK` a `List` and
     * `collectToSet` a `Set`. [ModelState] equality is structural, so a modelled `Int` count
     * would mismatch every kernel terminal and nothing but this test would say so.
     */
    @Test
    fun `each registered group-by aggregate carries the kernel aggregator's value type`() {
        val group = listOf(ModelState.SetState(setOf("g" to 3, "g" to 7, "h" to 1)))

        fun valuesOf(id: String): Map<Any?, Any?> =
            ((OperatorCatalog.entry(id)!!.model as OperatorModel).evaluate(group) as ModelState.MapState).entries

        valuesOf(CoreOperators.Ids.GROUP_BY_COUNT) shouldBe mapOf("g" to 2L, "h" to 1L)
        valuesOf(CoreOperators.Ids.GROUP_BY_SUM) shouldBe mapOf("g" to 10L, "h" to 1L)
        valuesOf(CoreOperators.Ids.GROUP_BY_AVG) shouldBe mapOf("g" to 5.0, "h" to 1.0)
        valuesOf(CoreOperators.Ids.GROUP_BY_MIN) shouldBe mapOf("g" to 3L, "h" to 1L)
        valuesOf(CoreOperators.Ids.GROUP_BY_MAX) shouldBe mapOf("g" to 7L, "h" to 1L)
        valuesOf(CoreOperators.Ids.GROUP_BY_TOP_K) shouldBe
            mapOf("g" to listOf(7L, 3L), "h" to listOf(1L))
        valuesOf(CoreOperators.Ids.GROUP_BY_COLLECT_TO_SET) shouldBe
            mapOf("g" to setOf("g" to 3, "g" to 7), "h" to setOf("h" to 1))

        withClue("GroupByCell.global folds to ONE constant-key group, and its outlet is a map") {
            valuesOf(CoreOperators.Ids.GROUP_BY_GLOBAL) shouldBe mapOf("global" to 3L)
        }
    }

    /**
     * Group death at the registration site: an empty input yields an empty map for every
     * registered aggregate — never a key carrying the aggregator's identity value
     * (`[24-OP-GROUPBY-02]`, `[ORA1-MODEL-06]`). `groupByGlobal` included: the constant key
     * dies with its last element like any other.
     */
    @Test
    fun `every registered group-by yields an empty map for an empty input`() {
        val empty = listOf(ModelState.EMPTY_SET)

        (CoreOperators.Ids.GROUP_BY_AGGREGATES + CoreOperators.Ids.GROUP_BY_GLOBAL).forEach { id ->
            withClue(id) {
                (OperatorCatalog.entry(id)!!.model as OperatorModel).evaluate(empty) shouldBe
                    ModelState.EMPTY_MAP
            }
        }
    }

    @Test
    fun `registering twice is refused rather than silently rebinding`() {
        val failure = runCatching { CoreOperators.registerAll() }.exceptionOrNull()

        failure.shouldNotBeNull()
        failure.shouldBeInstanceOf<IllegalStateException>()
    }

    private fun serialize(value: Any) {
        ObjectOutputStream(ByteArrayOutputStream()).use { it.writeObject(value) }
    }
}
