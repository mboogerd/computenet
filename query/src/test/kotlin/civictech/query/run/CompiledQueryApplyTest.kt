package civictech.query.run

import civictech.cell.Propagate
import civictech.cell.data.SetApi
import civictech.cell.data.view.SetView
import civictech.cell.graph.TypedRef
import civictech.cell.graph.lookup
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.query.lower.Lowering
import civictech.query.lower.LoweringResult
import civictech.query.lower.PlanFixtures
import civictech.query.parse.ParseResult
import civictech.query.parse.QueryParser
import civictech.query.plan.LogicalPlan
import civictech.query.plan.Planner
import civictech.query.schema.Catalog
import civictech.query.schema.Row
import civictech.testkit.SimWorld
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

/**
 * Applies a compiled query to a [SimWorld] host and checks it behaves like the live graph it
 * describes — `[QRY1-LOWER-04]`, BS-5, BS-12, `[QRY1-LOWER-10]`. `CompiledQueryApplyTest`
 * only; `RunShapeTest` covers the run package's data-shape guarantees.
 */
class CompiledQueryApplyTest {

    private fun parse(source: String, catalog: Catalog) =
        (QueryParser.parse(source, catalog) as ParseResult.Parsed).query

    private fun plan(source: String, catalog: Catalog): LogicalPlan = Planner.plan(parse(source, catalog))

    /** Compiles [source] against [catalog]; fails the test (not silently) if lowering refuses. */
    private fun compile(source: String, catalog: Catalog): CompiledQuery {
        val logicalPlan = plan(source, catalog)
        val lowered = Lowering.lower(logicalPlan, catalog).shouldBeInstanceOf<LoweringResult.Lowered>()
        return CompiledQuery.from(lowered, logicalPlan)
    }

    private fun row(vararg values: Int): Row = Row(values.toList())

    /** Subscribes a materialized [SetView] to [ref]'s outlet on [world]'s host. */
    private fun viewOf(world: SimWorld, ref: TypedRef<SetApi<Row>>): SetView<Row> {
        val view = SetView<Row>()
        world.host.lookup(ref)!!.outlet.subscribe(Use.fixed(Propagate { delta -> view.apply(delta) }, PortRef.generate()))
        return view
    }

    private fun writerOf(world: SimWorld, ref: TypedRef<SetApi<Row>>) = world.host.lookup(ref)!!.inlet.call

    @Test
    fun `applyTo returns a CellRef for every spawn handle and throws nothing, for example 1`() {
        val catalog = PlanFixtures.catalog("r" to 2)
        val compiled = compile("q(X) :- r(X, Y), Y > 3.", catalog)
        val world = SimWorld(seed = 1)

        val applied = compiled.applyTo(world.host.managementInlet)

        val expectedHandles = compiled.spec.lowered()
            .filterIsInstance<civictech.cell.graph.SpawnStep>()
            .map { it.handle }
            .toSet()
        applied.handles.keys shouldBe expectedHandles
    }

    @Test
    fun `applyTo returns a CellRef for every spawn handle, for a multi-rule head`() {
        val catalog = PlanFixtures.catalog("r" to 2, "s" to 1)
        val compiled = compile(
            """
            q(X) :- r(X, Y), Y > 3.
            q(X) :- s(X).
            """.trimIndent(),
            catalog,
        )
        val world = SimWorld(seed = 2)

        val applied = compiled.applyTo(world.host.managementInlet)

        val expectedHandles = compiled.spec.lowered()
            .filterIsInstance<civictech.cell.graph.SpawnStep>()
            .map { it.handle }
            .toSet()
        applied.handles.keys shouldBe expectedHandles
    }

    @Test
    fun `BS-5 first half - antijoin emits without the witness ever being written`() {
        val catalog = PlanFixtures.catalog("r" to 1, "s" to 1)
        val compiled = compile("q(X) :- r(X), not s(X).", catalog)
        val world = SimWorld(seed = 3)
        val applied = compiled.applyTo(world.host.managementInlet)

        val r = writerOf(world, applied.sources.getValue("r"))
        val q = viewOf(world, TypedRef<SetApi<Row>>(applied.outputs.getValue("q").ref))

        r.add(row(1))
        r.add(row(2))
        r.add(row(3))
        // s is never written.
        world.runToIdle()

        q.current() shouldBe setOf(row(1), row(2), row(3))
    }

    @Test
    fun `BS-5 second half - a join against a never-written witness leaves q empty and runToIdle returns`() {
        // Same script as the first half (write only r, never s) but a plain join instead of
        // the antijoin: an empty s means the join has nothing to match, so q stays empty —
        // and, unlike an unbounded wait for s, the budgeted runToIdle still quiesces and
        // returns rather than hanging on a source that legitimately never produces anything.
        val catalog = PlanFixtures.catalog("r" to 1, "s" to 1)
        val compiled = compile("q(X) :- r(X), s(X).", catalog)
        val world = SimWorld(seed = 4)
        val applied = compiled.applyTo(world.host.managementInlet)

        val r = writerOf(world, applied.sources.getValue("r"))
        val q = viewOf(world, TypedRef<SetApi<Row>>(applied.outputs.getValue("q").ref))

        r.add(row(1))
        r.add(row(2))
        r.add(row(3))
        // s is never written.
        world.runToIdle() // returns under budget: quiescence, not a timeout.

        q.current() shouldBe emptySet()
    }

    @Test
    fun `BS-12 - a serialized-and-revived CompiledQuery applies and answers identically to the original`() {
        val catalog = PlanFixtures.catalog("r" to 2, "t" to 2)
        val original = compile("j(X, Z) :- r(X, Y), t(Y, Z), Y > 0.", catalog)

        val bytes = ByteArrayOutputStream().also { bos ->
            ObjectOutputStream(bos).use { it.writeObject(original) }
        }.toByteArray()
        val revived = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() as CompiledQuery }
        revived shouldBe original

        val worldA = SimWorld(seed = 5)
        val worldB = SimWorld(seed = 5)
        val appliedA = original.applyTo(worldA.host.managementInlet)
        val appliedB = revived.applyTo(worldB.host.managementInlet)

        val rA = writerOf(worldA, appliedA.sources.getValue("r"))
        val tA = writerOf(worldA, appliedA.sources.getValue("t"))
        val jA = viewOf(worldA, TypedRef<SetApi<Row>>(appliedA.outputs.getValue("j").ref))
        val rB = writerOf(worldB, appliedB.sources.getValue("r"))
        val tB = writerOf(worldB, appliedB.sources.getValue("t"))
        val jB = viewOf(worldB, TypedRef<SetApi<Row>>(appliedB.outputs.getValue("j").ref))

        // Same script, driven independently against each host.
        val script: (civictech.cell.data.SetOps<Row>, civictech.cell.data.SetOps<Row>) -> Unit = { r, t ->
            r.add(row(1, 10))
            r.add(row(2, 20))
            t.add(row(10, 100))
            t.add(row(20, 200))
            r.remove(row(2, 20))
        }
        script(rA, tA)
        script(rB, tB)
        worldA.runToIdle()
        worldB.runToIdle()

        jA.current() shouldBe jB.current()
        jA.current() shouldBe setOf(row(1, 100)) // j(X, Z) projects away the join column Y.
    }

    @Test
    fun `QRY1-LOWER-10 - a row contributed by two rule heads is held once, live, and survives one contributor's retraction`() {
        val catalog = PlanFixtures.catalog("r" to 1, "s" to 1)
        val compiled = compile(
            """
            q(X) :- r(X).
            q(X) :- s(X).
            """.trimIndent(),
            catalog,
        )
        val world = SimWorld(seed = 6)
        val applied = compiled.applyTo(world.host.managementInlet)

        val r = writerOf(world, applied.sources.getValue("r"))
        val s = writerOf(world, applied.sources.getValue("s"))
        val q = viewOf(world, TypedRef<SetApi<Row>>(applied.outputs.getValue("q").ref))

        r.add(row(1))
        s.add(row(1))
        world.runToIdle()
        q.current() shouldBe setOf(row(1))

        r.remove(row(1))
        world.runToIdle()
        q.current() shouldBe setOf(row(1)) // s still holds it live.
    }

    @Test
    fun `OutputShape - a grouped-count root reports MAP_BY_GROUP where lowering succeeds, else refuses (not yet lowered)`() {
        // On this base a root GroupAggregate always refuses (Lowering.NOT_YET_LOWERED); this
        // asserts that refusal rather than stubbing the completion task's rule (bead
        // instruction: "assert the refusal and note it, do not stub").
        val catalog = PlanFixtures.catalog("r" to 2)
        val logicalPlan = plan("@count cnt(X, C) :- r(X, C).", catalog)
        val result = Lowering.lower(logicalPlan, catalog)

        val refused = result.shouldBeInstanceOf<LoweringResult.Refused>()
        refused.refusals.shouldHaveExactlyOneRefusalNotYetLowered()
    }

    private fun List<civictech.query.lower.LoweringRefusal>.shouldHaveExactlyOneRefusalNotYetLowered() {
        this.size shouldBe 1
        this.single().reason shouldBe Lowering.NOT_YET_LOWERED
    }
}
