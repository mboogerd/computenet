package civictech.query.run

import civictech.cell.Propagate
import civictech.cell.data.delta.CounterDelta
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.view.SetView
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
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The named `[QRY1-API-03]` witness: drives one compiled query — a join with a projection, a
 * grouped count and a scalar count, three roots over two EDB relations — end to end through
 * `applied.sources`, [AppliedQuery.setOutput]/[AppliedQuery.mapOutput]/[AppliedQuery.counterOutput]
 * and kernel views ALONE. The point of this file is what it does *not* say: no test here may
 * name a spawn handle (the `src:` prefix `Lowering.sourceHandle` mints, or the
 * `<root>/<counter>:` shape a non-source spawn's handle takes) or a port (the four names
 * `Lowering` connects: outlet, inlet, left, right) as a string literal — every reference to the
 * running graph goes through a relation name plus the typed accessors above, or a plain member
 * access such as `.outlet`/`.inlet` (not a string). [selfCheckNoHandleOrPortLiteral] reads this
 * file's own source and fails the property rather than leaving it as a comment nobody re-checks.
 */
class CompiledQuerySymbolTableTest {

    private fun parse(source: String, catalog: Catalog) =
        (QueryParser.parse(source, catalog) as ParseResult.Parsed).query

    private fun plan(source: String, catalog: Catalog): LogicalPlan = Planner.plan(parse(source, catalog))

    private fun compile(source: String, catalog: Catalog): CompiledQuery {
        val logicalPlan = plan(source, catalog)
        val lowered = Lowering.lower(logicalPlan, catalog).shouldBeInstanceOf<LoweringResult.Lowered>()
        return CompiledQuery.from(lowered, logicalPlan)
    }

    private fun row(vararg values: Int): Row = Row(values.toList())

    private val catalog = PlanFixtures.catalog("r" to 2, "s" to 2)

    private val source = """
        j(X, Z) :- r(X, Y), s(Y, Z).
        @count cnt(X, C) :- r(X, C).
        @count tot(X) :- s(X, Y).
    """.trimIndent()

    @Test
    fun `QRY1-API-03 - a join, a grouped count and a scalar count answer correctly through the symbol table alone`() {
        val compiled = compile(source, catalog)
        compiled.outputShapes["j"] shouldBe OutputShape.SET_OF_ROWS
        compiled.outputShapes["cnt"] shouldBe OutputShape.MAP_BY_GROUP
        compiled.outputShapes["tot"] shouldBe OutputShape.COUNTER

        val world = SimWorld(seed = 42)
        val applied = compiled.applyTo(world.host.managementInlet)

        val r = world.host.lookup(applied.sources.getValue("r"))!!.inlet.call
        val s = world.host.lookup(applied.sources.getValue("s"))!!.inlet.call

        val j = SetView<Row>()
        world.host.lookup(applied.setOutput("j"))!!
            .outlet.subscribe(Use.fixed(Propagate { delta -> j.apply(delta) }, PortRef.generate()))

        val cnt = mutableMapOf<Row, Long>()
        world.host.lookup(applied.mapOutput("cnt"))!!
            .outlet.subscribe(
                Use.fixed(
                    Propagate { delta ->
                        @Suppress("UNCHECKED_CAST")
                        val typed = delta as MapDelta<Row, Long>
                        typed.puts.forEach { (key, value) -> cnt[key] = value }
                        typed.removals.forEach { key -> cnt.remove(key) }
                    },
                    PortRef.generate(),
                ),
            )

        var tot = 0L
        world.host.lookup(applied.counterOutput("tot"))!!
            .outlet.subscribe(
                Use.fixed(Propagate { delta: CounterDelta -> tot += delta.amount }, PortRef.generate()),
            )

        r.add(row(1, 10))
        r.add(row(1, 11))
        r.add(row(2, 20))
        r.add(row(3, 10))
        s.add(row(10, 100))
        s.add(row(20, 200))
        world.runToIdle()

        withClue("j(X, Z) :- r(X, Y), s(Y, Z) - joins on Y, projects it away") {
            j.current() shouldBe setOf(row(1, 100), row(2, 200), row(3, 100))
        }
        withClue("cnt(X, C) :- r(X, C) grouped by X - per-group row counts") {
            cnt shouldBe mapOf(row(1) to 2L, row(2) to 1L, row(3) to 1L)
        }
        withClue("tot(X) :- s(X, Y) scalar COUNT - distinct s body rows") {
            tot shouldBe 2L
        }
    }

    @Test
    fun `setOutput on a grouped output throws naming the relation, the requested shape and the compiled shape`() {
        val applied = compile(source, catalog).applyTo(SimWorld(seed = 43).host.managementInlet)
        val failure = shouldThrow<IllegalArgumentException> { applied.setOutput("cnt") }
        failure.message!!.shouldContain("cnt")
        failure.message!!.shouldContain("SET_OF_ROWS")
        failure.message!!.shouldContain("MAP_BY_GROUP")
    }

    @Test
    fun `counterOutput on a set output throws naming the relation, the requested shape and the compiled shape`() {
        val applied = compile(source, catalog).applyTo(SimWorld(seed = 44).host.managementInlet)
        val failure = shouldThrow<IllegalArgumentException> { applied.counterOutput("j") }
        failure.message!!.shouldContain("j")
        failure.message!!.shouldContain("COUNTER")
        failure.message!!.shouldContain("SET_OF_ROWS")
    }

    @Test
    fun `mapOutput on an unknown relation throws naming the known outputs`() {
        val applied = compile(source, catalog).applyTo(SimWorld(seed = 45).host.managementInlet)
        val failure = shouldThrow<NoSuchElementException> { applied.mapOutput("does-not-exist") }
        failure.message!!.shouldContain("does-not-exist")
        failure.message!!.shouldContain("cnt")
        failure.message!!.shouldContain("j")
        failure.message!!.shouldContain("tot")
    }

    /**
     * Reads this very file's own source and fails if it contains a string literal naming a
     * spawn handle or a port (see the class KDoc for the list). The forbidden search tokens
     * below are each assembled from two pieces joined at runtime, deliberately, so this method
     * never spells a forbidden quoted port literal out contiguously on disk — otherwise this
     * very check would trip on itself. A Gradle Test task's working directory is the project
     * directory (the idiom `ModuleDependencyTest` relies on), so this resolves to
     * `query/src/test/kotlin/...`.
     */
    @Test
    fun `selfCheckNoHandleOrPortLiteral - this file's own source names no spawn handle or port as a string literal`() {
        val path = "src/test/kotlin/civictech/query/run/CompiledQuerySymbolTableTest.kt"
        val file = File(path)
        file.isFile shouldBe true
        val text = file.readText()

        val q = '"'
        val forbiddenPortLiterals = listOf(
            "out" + "let",
            "in" + "let",
            "le" + "ft",
            "rig" + "ht",
        ).map { "$q$it$q" }
        val forbiddenHandlePrefix = "$q" + ("sr" + "c:")
        val forbiddenRootCounterShape = Regex("$q" + """[A-Za-z_][A-Za-z0-9_]*/\d+:""")

        val foundPortLiteral = forbiddenPortLiterals.filter { text.contains(it) }
        withClue("port-name string literals found: $foundPortLiteral") {
            foundPortLiteral shouldBe emptyList()
        }
        withClue("a '$forbiddenHandlePrefix...' source-handle string literal was found") {
            text.contains(forbiddenHandlePrefix) shouldBe false
        }
        withClue("a '<root>/<counter>:' spawn-handle-shaped string literal was found") {
            forbiddenRootCounterShape.containsMatchIn(text) shouldBe false
        }
    }
}
