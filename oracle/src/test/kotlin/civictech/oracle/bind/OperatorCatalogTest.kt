package civictech.oracle.bind

import civictech.cell.graph.CellFactory
import civictech.oracle.model.ElementShape
import civictech.oracle.model.ReferenceOp
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The paired-registration seam: BS-17 in both directions ([ORA1-API-02], the enforcement
 * point of `[ORA1-GEN-08]`) and the shape read-back ([ORA1-API-03]).
 */
class OperatorCatalogTest {

    /**
     * A factory that would build a cell if anything asked it to. Nothing does: the catalog
     * stores bindings, it never constructs, so a throwing body is the honest stand-in — a
     * real `FilterCell` factory here would only prove that this test can build a filter.
     * `CellFactory` is `civictech.cell.graph.CellFactory` (GraphDsl.kt), i.e. the same
     * `(CellRef) -> Cell` the host resolves a spawn step through.
     */
    private val someKernelFactory = CellFactory { ref ->
        throw UnsupportedOperationException("OperatorCatalogTest never constructs a cell (ref=$ref)")
    }

    private val someReferenceModel = object : ReferenceOp {}

    private val someShape = ShapeRule.unary(
        input = ElementShape.SetOf(ElementShape.Scalar),
        output = ElementShape.SetOf(ElementShape.Scalar),
    )

    @AfterEach
    fun emptyTheProcessWideCatalog() {
        OperatorCatalog.reset()
    }

    @Test
    fun `BS-17 a kernel factory registered without a reference model fails at registration naming the id`() {
        val failure = assertThrows<IllegalArgumentException> {
            OperatorCatalog.register(
                id = "filter",
                shape = someShape,
                kernel = someKernelFactory,
                model = null,
            )
        }

        // Mutation-checking this line: register()'s failure message names the id TWICE (in
        // "Catalog id '$id' cannot be registered" and again in "'$id' has not been added"), so
        // stripping only the FIRST occurrence leaves all three BS-17 cases GREEN and reads as
        // an unconstrained clause. Strip both. Measured both ways 2026-08-17: one occurrence
        // removed -> 7 tests, 0 failures; both removed -> these three red.
        //
        // The assertion is a substring match on a generic English word, so it would also pass
        // vacuously if the message ever gained "filter" as prose. It has none today (that is
        // what the both-occurrences mutation proves). If prose is added, tighten this to the
        // quoted form `shouldContain "'filter'"` or give the test a distinctive id.
        failure.message!! shouldContain "filter"
        failure.message!! shouldContain "reference model"

        // The vocabulary must not contain a half-bound id: [ORA1-GEN-08] is satisfied by a
        // half binding never existing, not by a later check catching one.
        ("filter" in OperatorCatalog) shouldBe false
        OperatorCatalog.entry("filter") shouldBe null
        OperatorCatalog.ids() shouldBe emptySet<String>()
    }

    @Test
    fun `BS-17 a reference model registered without a kernel factory fails at registration naming the id`() {
        val failure = assertThrows<IllegalArgumentException> {
            OperatorCatalog.register(
                id = "filter",
                shape = someShape,
                kernel = null,
                model = someReferenceModel,
            )
        }

        failure.message!! shouldContain "filter"
        failure.message!! shouldContain "kernel cell factory"

        ("filter" in OperatorCatalog) shouldBe false
        OperatorCatalog.entry("filter") shouldBe null
        OperatorCatalog.ids() shouldBe emptySet<String>()
    }

    @Test
    fun `BS-17 neither binding at all fails naming the id and both absences`() {
        val failure = assertThrows<IllegalArgumentException> {
            OperatorCatalog.register(id = "filter", shape = someShape, kernel = null, model = null)
        }

        failure.message!! shouldContain "filter"
        failure.message!! shouldContain "kernel cell factory"
        failure.message!! shouldContain "reference model"
        ("filter" in OperatorCatalog) shouldBe false
    }

    @Test
    fun `a paired registration lands and both bindings read back`() {
        val entry = OperatorCatalog.register(
            id = "filter",
            shape = someShape,
            kernel = someKernelFactory,
            model = someReferenceModel,
        )

        ("filter" in OperatorCatalog) shouldBe true
        OperatorCatalog.ids() shouldBe setOf("filter")
        entry.kernel shouldBe someKernelFactory
        entry.model shouldBe someReferenceModel
        OperatorCatalog.entry("filter") shouldBe entry
    }

    @Test
    fun `ORA1-API-03 a newly registered entry's ShapeRule reads back through the public API`() {
        // A ternary operator over a shape no enum in this repo can name: a set of 4-tuples
        // joined with a map to a map of sets. If the catalog can carry this without a code
        // change, a generator reading shapes off the catalog picks up a consumer's operator
        // without a generator edit — which is what [ORA1-API-03] asks for.
        val quads = ElementShape.SetOf(ElementShape.Tuple(4))
        val index = ElementShape.MapOf(ElementShape.Scalar, ElementShape.Scalar)
        val grouped = ElementShape.MapOf(ElementShape.Scalar, ElementShape.SetOf(ElementShape.Tuple(4)))
        val rule = ShapeRule(inputs = listOf(quads, index, ElementShape.Scalar), output = grouped)

        OperatorCatalog.register(
            id = "consumer-supplied-op",
            shape = rule,
            kernel = someKernelFactory,
            model = someReferenceModel,
        )

        val readBack = OperatorCatalog.shapeOf("consumer-supplied-op")!!
        readBack shouldBe rule
        readBack.arity shouldBe 3
        readBack.inputs shouldContainExactly listOf(quads, index, ElementShape.Scalar)
        readBack.output shouldBe grouped

        // ...and off the entry, which is the form a sweep enumerating all() reads.
        OperatorCatalog.all().single().shape shouldBe rule
    }

    @Test
    fun `re-registering an id fails rather than silently replacing its bindings`() {
        OperatorCatalog.register("filter", someShape, someKernelFactory, someReferenceModel)

        val failure = assertThrows<IllegalStateException> {
            OperatorCatalog.register("filter", someShape, someKernelFactory, someReferenceModel)
        }

        failure.message!! shouldContain "filter"
        OperatorCatalog.ids() shouldBe setOf("filter")
    }

    @Test
    fun `a blank id is rejected`() {
        assertThrows<IllegalArgumentException> {
            OperatorCatalog.register("  ", someShape, someKernelFactory, someReferenceModel)
        }
        OperatorCatalog.ids() shouldBe emptySet<String>()
    }
}
