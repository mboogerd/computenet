package civictech.query.diag

import civictech.cell.Propagate
import civictech.cell.data.SetApi
import civictech.cell.data.view.SetView
import civictech.cell.graph.TypedRef
import civictech.cell.graph.lookup
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.query.QueryCompiler
import civictech.query.schema.AttrType
import civictech.query.schema.Attribute
import civictech.query.schema.Catalog
import civictech.query.schema.RelationSchema
import civictech.query.schema.Row
import civictech.testkit.SimWorld
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * [QRY1-API-05] affirmative half (computenet-cab.7.3, cab.7-D15): a [Catalog] naming a relation
 * with no declared row key ([RelationSchema.rowKey] `null`) still compiles the queries over it
 * that are key-preserving by construction — identity and reordering projections, a join of two
 * keyless relations, and an antijoin whose witness itself projects a keyless relation. The
 * rejecting half of [QRY1-API-05] (a `COUNT`/`SUM`/`AVG` fed by a keyless relation) lives in
 * `civictech.query.plan.BagSemanticsTest`.
 *
 * **Explicitly absent.** This test makes no assertion about a `COUNT`, `SUM` or `AVG` applied
 * *directly* to a keyless relation (`@count c(X) :- e(X, Y).` over keyless `e`) — that shape is
 * computenet-afnwl, parked pending a human decision on whether it should compile. Only the
 * negative control below asserts a rejection, and that rejection holds under both of
 * computenet-afnwl's options (a keyless relation feeding a `SUM` *through a projection* stays
 * refused either way).
 *
 * Two non-vacuity observations recorded from manual, reverted mutations of this fixture (per the
 * task's Verification note; not asserted in code — the suite's own catalogs already establish
 * the property without a second one): (1) declaring `rowKey = setOf("a", "b")` on `keylessCatalog`'s
 * `r` left every `Compiled` assertion in this file passing unchanged — the property under test is
 * "keyless does not disable", not "keyless is required". (2) declaring `rowKey = setOf("v")` on
 * [keylessPair]'s `r` (its projection `p(V) :- r(K, V).` keeps exactly the attribute `v`, so the
 * key survives the projection) flipped the negative control from `Rejected` to `Compiled` —
 * confirming the control genuinely depends on `r` being keyless, not on some other property of
 * the query.
 */
class KeylessRelationTest {

    /** `r(a, b)` and `s(b, c)`, both with no declared row key ([RelationSchema.rowKey] `null`). */
    private val keylessCatalog = Catalog(
        mapOf(
            "r" to RelationSchema(listOf(Attribute("a", AttrType.INT), Attribute("b", AttrType.INT)), rowKey = null),
            "s" to RelationSchema(listOf(Attribute("b", AttrType.INT), Attribute("c", AttrType.INT)), rowKey = null),
        ),
    )

    /** A minimal one-relation catalog for the negative control, so its head name `t` cannot collide with [keylessCatalog]. */
    private fun keylessPair(): Catalog = Catalog(
        mapOf("r" to RelationSchema(listOf(Attribute("k", AttrType.INT), Attribute("v", AttrType.INT)), rowKey = null)),
    )

    private fun row(vararg values: Int): Row = Row(values.toList())

    /** Compiles [source] against [catalog], failing the test (not silently) if it is [CompileResult.Rejected]. */
    private fun compiled(source: String, catalog: Catalog = keylessCatalog): CompileResult.Compiled =
        withClue(source) { QueryCompiler.compile(source, catalog).shouldBeInstanceOf<CompileResult.Compiled>() }

    /**
     * Applies [result]'s query to a fresh [SimWorld] host and asserts it named every relation in
     * [expectedSources] as an EDB source ([QRY1-LOWER-04] for keyless sources). `applyTo` itself
     * is `GraphSpec.applyTo`'s local, co-located, synchronous-failure path: reaching the
     * assertion below at all is the "no rejected link" half of the claim.
     */
    private fun assertAppliesCleanly(result: CompileResult.Compiled, vararg expectedSources: String) {
        val world = SimWorld(seed = 42)
        val applied = result.query.applyTo(world.host.managementInlet)
        withClue("sourceHandles of ${result.query.sourceHandles}") {
            applied.sources.keys shouldBe expectedSources.toSet()
        }
    }

    @Test
    fun `identity projection over a keyless relation compiles and applies cleanly`() {
        val result = compiled("q(A, B) :- r(A, B).")
        assertAppliesCleanly(result, "r")
    }

    @Test
    fun `a reordering projection carrying the full attribute set over a keyless relation compiles and applies cleanly`() {
        val result = compiled("q2(B, A) :- r(A, B).")
        assertAppliesCleanly(result, "r")
    }

    @Test
    fun `an equi-join of two keyless relations compiles and applies cleanly`() {
        val result = compiled("j(A, B, C) :- r(A, B), s(B, C).")
        assertAppliesCleanly(result, "r", "s")
    }

    @Test
    fun `an antijoin whose witness projects a keyless relation compiles and applies cleanly`() {
        val result = compiled(
            """
            k(A, B) :- r(A, B), not sb(B).
            sb(B) :- s(B, C).
            """.trimIndent(),
        )
        assertAppliesCleanly(result, "r", "s")
    }

    @Test
    fun `an order-only aggregate (max) over a keyless relation with no projection compiles`() {
        // BagSemantics.MULTIPLICITY_SENSITIVE (civictech.query.plan.BagSemantics) is only
        // {COUNT, SUM, AVG}; MAX is order-only and never refused as BAG_SEMANTICS_REQUIRED,
        // over a keyless input or otherwise. Verified against the source, not asserted here:
        // this test does not depend on that decision, only demonstrates it holds for MAX.
        val result = compiled("@max m(A, B) :- r(A, B).")
        assertAppliesCleanly(result, "r")
    }

    @Test
    fun `negative control - a keyless relation feeding a SUM through a projection stays Rejected with BAG_SEMANTICS_REQUIRED`() {
        val rejections = withClue("sum t(V) :- p(V). p(V) :- r(K, V).") {
            QueryCompiler.compile("@sum t(V) :- p(V).\np(V) :- r(K, V).", keylessPair())
                .shouldBeInstanceOf<CompileResult.Rejected>().rejections
        }
        withClue("$rejections") {
            rejections.map { it.code } shouldBe listOf(RejectionCode.BAG_SEMANTICS_REQUIRED)
        }
    }

    @Test
    fun `k(A, B) applies without ever writing the antijoin witness relation`() {
        val result = compiled(
            """
            k(A, B) :- r(A, B), not sb(B).
            sb(B) :- s(B, C).
            """.trimIndent(),
        )
        val world = SimWorld(seed = 43)
        val applied = result.query.applyTo(world.host.managementInlet)

        val r = world.host.lookup(applied.sources.getValue("r"))!!.inlet.call
        val view = SetView<Row>()
        world.host.lookup(TypedRef<SetApi<Row>>(applied.outputs.getValue("k").ref))!!
            .outlet.subscribe(Use.fixed(Propagate { delta -> view.apply(delta) }, PortRef.generate()))

        r.add(row(1, 10))
        r.add(row(2, 20))
        // s is never written.
        world.runToIdle()

        withClue("no witness row was ever written for either B value") {
            view.current() shouldBe setOf(row(1, 10), row(2, 20))
        }
    }
}
