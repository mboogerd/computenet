package civictech.query.run

import civictech.oracle.model.ModelState
import civictech.oracle.run.Reference
import civictech.oracle.run.RunOutcome
import civictech.query.QueryCompiler
import civictech.query.architecture.HierarchyCompleteness
import civictech.query.ast.AggregateKind
import civictech.query.diag.CompileResult
import civictech.query.diag.RejectionCode
import civictech.query.parse.ParseResult
import civictech.query.parse.QueryParser
import civictech.query.plan.GroupAggregate
import civictech.query.plan.PlanNode
import civictech.query.plan.PlanOrder
import civictech.query.schema.Catalog
import civictech.query.schema.Row
import civictech.testkit.forEachSeed
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The generated-corpus sweep (computenet-cab.6.5; epic computenet-cab §4.6 `[QRY1-ORA-04]`,
 * `[QRY1-ORA-05]`, `[QRY1-ORA-06]`, §8, §9 risk 5).
 *
 * Per seed, one [QueryGenerator] case:
 *
 * - **Admissible** — `QueryCompiler.compile(query)` must be `Compiled` (the front door, which
 *   also runs `BagSemantics`, agrees the case is admissible); the rendered source must parse back
 *   to the generated AST; every plan root must have a compared terminal; then
 *   `QueryCase.assertSuccess` over a [QueryScripts] add/remove script at the configured deletion
 *   ratio, and a per-root mismatch control. A refusal of an admissible case is a generator bug and
 *   fails naming the recipe.
 * - **Inadmissible** — `QueryCompiler.compile` over both the AST and the source must be
 *   `CompileResult.Rejected` with the expected [RejectionCode] among its codes. No `SimWorld` is
 *   built and no spec is applied for such a case; `Rejected` carries no `GraphSpec` to apply.
 *
 * **Non-vacuity (AMENDS computenet-cab.6.5 from cab.6.2's review).** `DifferentialRunner` compares
 * only the terminals the `CaseGraph` names, so every admissible case asserts that
 * `CompiledQuery.outputShapes` (which `QueryCase.buildGraph` turns into terminals) covers every
 * `LogicalPlan` root, and then, per root, substitutes a reference that is wrong for that root alone
 * and requires a `Mismatch` naming it. A root with no linked terminal, or a comparison that is not
 * armed, fails the seed.
 *
 * **Seeds.** `0 until seedCount()`: the `query.seeds` system property (`-Pquery.seeds=N`, wired in
 * `query/build.gradle.kts`), default [DEFAULT_SEEDS]. The report is printed on every run; its
 * distribution floors are asserted only at a seed count of at least [DEFAULT_SEEDS] — at fewer
 * seeds a construct can legitimately go undrawn.
 */
class QuerySweepTest {

    companion object {
        const val DEFAULT_SEEDS = 200

        /** Seeds this sweep runs: `-Pquery.seeds=N`, else [DEFAULT_SEEDS] (epic §8). */
        fun seedCount(): Int = System.getProperty("query.seeds")?.toInt() ?: DEFAULT_SEEDS

        val CONFIG = GeneratorConfig()

        /** Every [RejectionCode] some [InjectedConstruct] expects. */
        val EXPECTED_CODES: Set<RejectionCode> = InjectedConstruct.entries.map { it.code }.toSet()
    }

    /** What the sweep saw, accumulated across seeds. */
    private class Tally {
        var admissible = 0
        var inadmissible = 0
        val recipes = sortedMapOf<String, Int>()
        val codes = sortedMapOf<RejectionCode, Int>()
        val kinds = sortedMapOf<String, Int>()
        val kindClasses = LinkedHashSet<Class<*>>()
        val aggregates = sortedMapOf<AggregateKind, Int>()
        var scripts = QueryScripts.ScriptStats.ZERO
        var chainCases = 0
        var chainDeletionCases = 0
        var controlledRoots = 0

        fun render(config: GeneratorConfig, seeds: Int): String = buildString {
            val total = admissible + inadmissible
            appendLine("QuerySweepTest report — seeds=$seeds")
            appendLine("  admissibleFraction configured=${config.admissibleFraction} achieved=${fraction(admissible, total)} ($admissible admissible / $inadmissible inadmissible)")
            appendLine("  recipes: $recipes")
            appendLine("  rejection codes (expected ${EXPECTED_CODES.sorted()}): $codes")
            appendLine("  PlanNode kinds: $kinds")
            appendLine("  AggregateKinds: $aggregates")
            appendLine("  scripts: deletionRatio=${config.deletionRatio} steps=${config.steps} adds=${scripts.adds} removes=${scripts.removes} (${fraction(scripts.removes, scripts.ops)} of ops)")
            appendLine("  join chains (>=3 atoms): $chainCases cases, $chainDeletionCases with a deletion on a chain relation")
            append("  mismatch controls: $controlledRoots roots")
        }

        private fun fraction(n: Int, d: Int): String = if (d == 0) "n/a" else "%.3f".format(n.toDouble() / d)
    }

    @Test
    fun `QRY1 §ORA-04§ORA-05§ORA-06 every generated admissible query agrees with BatchEvaluator at quiescence on every seed`() {
        val seeds = seedCount()
        val tally = Tally()
        try {
            forEachSeed(0L until seeds.toLong()) { seed -> sweepSeed(seed, CONFIG, tally) }
        } finally {
            println(tally.render(CONFIG, seeds))
        }
        if (seeds >= DEFAULT_SEEDS) assertReport(tally, CONFIG)
    }

    private fun sweepSeed(seed: Long, config: GeneratorConfig, tally: Tally) {
        val generated = QueryGenerator(seed, config).next()
        tally.recipes.merge(generated.recipe, 1, Int::plus)
        val label = "seed=$seed recipe=${generated.recipe}\n${generated.source}"
        if (generated.admissible) {
            tally.admissible++
            sweepAdmissible(seed, generated, config, tally, label)
        } else {
            tally.inadmissible++
            sweepInadmissible(generated, tally, label)
        }
    }

    private fun sweepAdmissible(seed: Long, generated: GeneratedQuery, config: GeneratorConfig, tally: Tally, label: String) {
        val compiled = QueryCompiler.compile(generated.query)
        if (compiled !is CompileResult.Compiled) {
            throw AssertionError("generator bug: admissible case was refused by QueryCompiler — $label\n$compiled")
        }
        val parsed = QueryParser.parse(generated.source, generated.catalog)
        withClue("generator bug: rendered source does not parse back to the generated AST — $label") {
            (parsed as? ParseResult.Parsed)?.query shouldBe generated.query
        }
        val case = try {
            QueryCase.compile(generated.source, generated.catalog)
        } catch (e: IllegalStateException) {
            throw AssertionError("generator bug: admissible case did not compile through QueryCase — $label", e)
        }
        val plan = case.compiled.plan
        withClue("non-vacuity: every plan root must have a compared terminal — $label") {
            case.compiled.outputShapes.keys shouldBe plan.roots.keys
        }

        val nodes = plan.roots.keys.sorted().flatMap { PlanOrder.allNodes(plan.roots.getValue(it)) }
        withClue("GeneratedQuery.kinds must be the compiled plan's node kinds — $label") {
            generated.kinds shouldBe nodes.mapTo(HashSet()) { it.javaClass }
        }
        nodes.map { it.javaClass }.distinct().forEach {
            tally.kindClasses += it
            tally.kinds.merge(it.simpleName, 1, Int::plus)
        }
        nodes.filterIsInstance<GroupAggregate>().map { it.aggregate.kind }.distinct().forEach {
            tally.aggregates.merge(it, 1, Int::plus)
        }

        // The script drives exactly the relations the compiled graph binds as sources: the runner
        // refuses a script over a source the case graph does not bind.
        val driven = Catalog(generated.catalog.relations.filterKeys { it in case.compiled.sourceHandles })
        val scripts = QueryScripts(seed, driven, steps = config.steps, deletionRatio = config.deletionRatio)
        tally.scripts += scripts.stats
        if (generated.joinChains.isNotEmpty()) {
            tally.chainCases++
            val hit = generated.joinChains.any { chain -> chain.any { (scripts.stats.removedRowsPerRelation[it] ?: 0) > 0 } }
            if (hit) tally.chainDeletionCases++
        }

        try {
            case.assertSuccess(seed, scripts.script)
        } catch (e: AssertionError) {
            throw AssertionError("recipe=${generated.recipe}\n${e.message}", e)
        }

        val reference = case.reference()
        plan.roots.keys.sorted().forEach { root ->
            val wrong = Reference { script ->
                val states = reference.evaluate(script)
                states + (root to perturbed(states.getValue(root)))
            }
            val outcome = case.check(seed, scripts.script, wrong)
            withClue("non-vacuity: a reference wrong for root '$root' alone must mismatch at that terminal — $label") {
                (outcome as? RunOutcome.Mismatch)?.terminal shouldBe root
            }
            tally.controlledRoots++
        }
    }

    private fun sweepInadmissible(generated: GeneratedQuery, tally: Tally, label: String) {
        val expected = checkNotNull(generated.expectedRejection)
        val fromAst = QueryCompiler.compile(generated.query)
        val fromSource = QueryCompiler.compile(generated.source, generated.catalog)
        listOf("AST" to fromAst, "source" to fromSource).forEach { (surface, result) ->
            val codes = (result as? CompileResult.Rejected)?.rejections?.map { it.code }
            withClue("inadmissible case via $surface must be Rejected with $expected; got $result — $label") {
                (codes != null && expected in codes) shouldBe true
            }
        }
        (fromAst as CompileResult.Rejected).rejections.map { it.code }.distinct().forEach { tally.codes.merge(it, 1, Int::plus) }
    }

    /** A state that differs from [state] and has its shape: the control's deliberately wrong answer. */
    private fun perturbed(state: ModelState): ModelState = when (state) {
        is ModelState.SetState -> ModelState.SetState(state.elements + Row(listOf("!control")))
        is ModelState.MapState -> ModelState.MapState(state.entries + ("!control" to "!control"))
        is ModelState.ScalarState -> ModelState.ScalarState(listOf("!control", state.value))
    }

    private fun assertReport(tally: Tally, config: GeneratorConfig) {
        val total = tally.admissible + tally.inadmissible
        withClue("achieved admissible fraction ${tally.admissible}/$total must be within 0.1 of configured ${config.admissibleFraction}") {
            (kotlin.math.abs(tally.admissible.toDouble() / total - config.admissibleFraction) <= 0.1) shouldBe true
        }
        withClue("every expected RejectionCode must be seen at least once; seen ${tally.codes}") {
            (EXPECTED_CODES - tally.codes.keys).shouldBeEmpty()
        }
        withClue("every PlanNode kind must be seen at least once; seen ${tally.kinds}") {
            HierarchyCompleteness.missingFrom(PlanNode::class.java, tally.kindClasses).shouldBeEmpty()
        }
        withClue("every AggregateKind must be seen at least once; seen ${tally.aggregates}") {
            (AggregateKind.entries.toSet() - tally.aggregates.keys).shouldBeEmpty()
        }
        withClue("removes must be at least 30% of ops [QRY1-ORA-06]; achieved ${tally.scripts}") {
            (tally.scripts.removes * 10 >= tally.scripts.ops * 3) shouldBe true
        }
        withClue("at least one deletion must hit a relation inside a >=3-atom join chain [QRY1-ORA-06]") {
            (tally.chainDeletionCases >= 1) shouldBe true
        }
        withClue("non-vacuity: the mismatch control must have run") {
            (tally.controlledRoots >= 1) shouldBe true
        }
    }

    @Test
    fun `QRY1 §ORA-05§PLAN-01§LOWER-05 generation is deterministic and its plans and specs are stable across compiles`() {
        val first = QueryGenerator(7, CONFIG)
        val second = QueryGenerator(7, CONFIG)
        var compiledCount = 0
        repeat(20) {
            val a = first.next()
            val b = second.next()
            a shouldBe b
            if (a.admissible) {
                val x = QueryCase.compile(a.source, a.catalog).compiled
                val y = QueryCase.compile(b.source, b.catalog).compiled
                x.plan shouldBe y.plan
                x.spec shouldBe y.spec
                compiledCount++
            }
        }
        withClue("non-vacuity: at least one admissible case compiled twice") { (compiledCount >= 1) shouldBe true }
        withClue("a different seed yields a different sequence") {
            (QueryGenerator(7, CONFIG).next() == QueryGenerator(8, CONFIG).next()) shouldBe false
        }
    }
}
