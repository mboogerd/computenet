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
