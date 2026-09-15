package civictech.query.run

import civictech.oracle.model.ModelState
import civictech.oracle.model.Script
import civictech.oracle.model.ScriptEvent
import civictech.oracle.model.SourceId
import civictech.oracle.model.SourceScript
import civictech.oracle.model.WriterId
import civictech.oracle.run.CaseExecution
import civictech.oracle.run.Reference
import civictech.oracle.run.RunOutcome
import civictech.query.ast.ComparisonOp
import civictech.query.lower.PlanFixtures
import civictech.query.plan.LogicalPlan
import civictech.query.schema.Row
import civictech.testkit.forEachSeed
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * The runner-integration pins of computenet-cab.6.2: a compiled query driven through
 * `DifferentialRunner.check` with `BatchEvaluator` as the reference ([QueryCase]), how a
 * throwing reference and a mismatch are reported, one fold per [OutputShape], and the
 * [QueryScripts] generator's own contract. Scenario tests (BS-*) are computenet-cab.6.3's.
 *
 * Non-vacuity of the runner path: the `ORA-09` test substitutes an empty-set reference on a
 * script with a non-empty join and gets a `Mismatch`, so the join sweep's `Success` is a
 * comparison that can fail, not a vacuous pass.
 */
class QueryDifferentialTest {

    private val f = PlanFixtures

    private val joinSource = "j(X, Z) :- r(X, Y), t(Y, Z)."
    private val joinCatalog = f.catalog("r" to 2, "t" to 2)

    private fun row(vararg values: Any?) = Row(values.toList())

    /** r = {(1, 2)}, t = {(2, 3)}: `j` answers {(1, 3)}. */
    private val nonEmptyJoinScript = Script(
        listOf(
            SourceScript(SourceId("r"), listOf(ScriptEvent.Add(WriterId("r"), row(1, 2)))),
            SourceScript(SourceId("t"), listOf(ScriptEvent.Add(WriterId("t"), row(2, 3)))),
        ),
    )

    @Test
    fun `QRY1 §ORA-01§ORA-02 a compiled join drives through DifferentialRunner check with BatchEvaluator as reference and succeeds on every seed`() {
        val case = QueryCase.compile(joinSource, joinCatalog)
        var totals = QueryScripts.ScriptStats.ZERO
        forEachSeed(0L until 50L) { seed ->
            val scripts = QueryScripts(seed, joinCatalog, steps = 40, deletionRatio = 0.4)
            totals += scripts.stats
            case.assertSuccess(seed, scripts.script)
        }
        withClue("achieved over the sweep: $totals — removes must be at least 30% of ops [QRY1-ORA-06]") {
            (totals.removes * 10) shouldBeGreaterThanOrEqual totals.ops * 3
            totals.removedRowsPerRelation.getValue("r") shouldBeGreaterThan 0
            totals.removedRowsPerRelation.getValue("t") shouldBeGreaterThan 0
        }
    }

    @Test
    fun `QRY1 §ORA-11 a throwing reference is reported as ModelEvaluationFailure, not Mismatch`() {
        val case = QueryCase.compile(joinSource, joinCatalog)

        val outcome = case.check(seed = 7L, script = nonEmptyJoinScript, reference = Reference { error("boom") })

        val failure = outcome.shouldBeInstanceOf<RunOutcome.ModelEvaluationFailure>()
        failure.seed shouldBe 7L
        failure.cause.message shouldBe "boom"
        outcome.describe(case, nonEmptyJoinScript) shouldStartWith "reference-evaluation failure"
    }

    @Test
    fun `QRY1 §ORA-11 BatchEvaluator refusing the plan it is handed is reported as ModelEvaluationFailure`() {
        // The compiled (lowerable) graph stays; only the reference side evaluates a different,
        // hand-built plan whose Select names a column its input lacks. Such a plan is not
        // lowerable, which is why it is only ever given to the reference.
        val case = QueryCase.compile(joinSource, joinCatalog)
        val refused = LogicalPlan(
            mapOf("j" to f.select(f.scan("r", "a0", "a1"), f.v("zz"), ComparisonOp.EQ, f.int(1))),
        )

        val outcome = case.check(seed = 3L, script = nonEmptyJoinScript, reference = case.reference(refused))

        val failure = outcome.shouldBeInstanceOf<RunOutcome.ModelEvaluationFailure>()
        failure.cause.shouldBeInstanceOf<IllegalStateException>()
        failure.cause.message shouldContain "BatchEvaluator cannot honour root 'j'"
        outcome.describe(case, nonEmptyJoinScript) shouldStartWith "reference-evaluation failure (BatchEvaluator threw)"
    }

    @Test
    fun `QRY1 §ORA-09 a mismatch names the query source, the LogicalPlan, the GraphSpec and the seed`() {
        val case = QueryCase.compile(joinSource, joinCatalog)
        val wrong = Reference { mapOf("j" to ModelState.SetState(emptySet())) }

        val outcome = case.check(seed = 11L, script = nonEmptyJoinScript, reference = wrong)

        val mismatch = outcome.shouldBeInstanceOf<RunOutcome.Mismatch>()
        mismatch.seed shouldBe 11L
        mismatch.terminal shouldBe "j"
        mismatch.actual shouldBe ModelState.SetState(setOf(row(1, 3)))
        mismatch.renderedGraphSpec shouldContain joinSource
        mismatch.renderedGraphSpec shouldContain case.compiled.plan.toString()
        mismatch.renderedGraphSpec shouldContain CaseExecution.renderSpec(case.compiled.spec)
        val described = outcome.describe(case)
        described shouldContain "replay: QueryCase.compile("
        described shouldContain "seed = 11L"
    }

    @Test
    fun `every OutputShape has a terminal fold`() {
        val catalog = f.catalog("r" to 2)
        val cases = mapOf(
            "q(X) :- r(X, Y)." to OutputShape.SET_OF_ROWS,
            "@count cnt(X, C) :- r(X, C)." to OutputShape.MAP_BY_GROUP,
            "@count c(X) :- r(X, Y)." to OutputShape.COUNTER,
        )
        cases.forEach { (source, shape) ->
            val case = QueryCase.compile(source, catalog)
            withClue(source) { case.compiled.outputShapes.values.single() shouldBe shape }

            // The reference really produces the shape's model state, so Success compares a
            // MapState / ScalarState rather than passing on an empty set.
            val expectedState = case.reference().evaluate(QueryScripts(0L, catalog, steps = 20, deletionRatio = 0.3).script)
                .values.single()
            withClue(source) {
                when (shape) {
                    OutputShape.SET_OF_ROWS -> expectedState.shouldBeInstanceOf<ModelState.SetState>()
                    OutputShape.MAP_BY_GROUP -> expectedState.shouldBeInstanceOf<ModelState.MapState>()
                    OutputShape.COUNTER -> expectedState.shouldBeInstanceOf<ModelState.ScalarState>()
                }
            }

            forEachSeed(0L until 10L) { seed ->
                case.assertSuccess(seed, QueryScripts(seed, catalog, steps = 20, deletionRatio = 0.3).script)
            }
        }
    }

    @Test
    fun `QRY1 §ORA-06 QueryScripts is deterministic from its seed, removes only held rows, and reports its split`() {
        val catalog = f.catalog("r" to 2, "t" to 1)
        forEachSeed(0L until 20L) { seed ->
            val a = QueryScripts(seed, catalog, steps = 60, deletionRatio = 0.5)
            val b = QueryScripts(seed, catalog, steps = 60, deletionRatio = 0.5)
            a.script shouldBe b.script
            a.stats shouldBe b.stats

            var adds = 0
            var removes = 0
            a.script.slices.forEach { slice ->
                val held = mutableSetOf<Any?>()
                var sliceRemoves = 0
                slice.events.forEach { event ->
                    event.writer shouldBe WriterId(slice.source.id)
                    when (event) {
                        is ScriptEvent.Add -> {
                            withClue("seed=$seed ${slice.source.id} re-adds held $event") { (event.element in held) shouldBe false }
                            held += event.element
                            adds++
                        }
                        is ScriptEvent.Remove -> {
                            withClue("seed=$seed ${slice.source.id} removes unheld $event") { (event.element in held) shouldBe true }
                            held -= event.element
                            removes++
                            sliceRemoves++
                        }
                        else -> error("unexpected event $event")
                    }
                }
                a.stats.removedRowsPerRelation.getValue(slice.source.id) shouldBe sliceRemoves
            }
            a.stats.adds shouldBe adds
            a.stats.removes shouldBe removes
            a.stats.ops shouldBe 60
        }
        (QueryScripts(1L, catalog, steps = 60, deletionRatio = 0.5).script ==
            QueryScripts(2L, catalog, steps = 60, deletionRatio = 0.5).script) shouldBe false
    }
}
