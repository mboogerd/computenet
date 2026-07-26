package civictech.concord.generator

import civictech.concord.schema.ApplyStep
import civictech.concord.schema.ConnectStep
import civictech.concord.schema.Generator
import civictech.concord.schema.IncrementalEqualsBatch
import civictech.concord.schema.Kind
import civictech.concord.schema.LateJoinEqualsEarly
import civictech.concord.schema.NoDeadLetters
import civictech.concord.schema.Profile
import civictech.concord.schema.QuiesceStep
import civictech.concord.schema.Scenario
import civictech.concord.schema.ViewsConverge
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Non-triviality + determinism guards for [ScenarioGenerator] (CONCORD-PLAN
 * honesty rule): a generative sweep that only passes because it emits trivial
 * graphs is worthless, so these assert the emitted graphs have **real operator
 * depth**, **exercise the whole vocabulary across the sweep**, and are
 * **reproducible** from the instance index. The end-to-end proof that every
 * generated graph actually executes and passes the four property checks is the
 * `24-GEN-01` case in `CorpusRunner`; this test guards the generator in isolation.
 */
class ScenarioGeneratorTest {

    private val spec = Scenario(
        id = "24-GEN-TEST",
        title = "generator unit fixture",
        profile = Profile.CORE,
        kind = Kind.GENERATIVE,
        generator = Generator(
            pipelineDepth = listOf(1, 4),
            vocabulary = listOf("set-source", "filter", "map", "union", "intersect", "join", "group-by", "count"),
            ops = 200,
            lateJoiner = true,
            instances = 40,
        ),
    )

    @Test
    fun `every generated instance has a real graph, an op stream, and the standard checks`() {
        for (i in 0 until 40) {
            val s = ScenarioGenerator.generate(spec, i)
            val cells = s.graph!!.cells
            // at least one source + at least one operator + one view (never a bare source→view)
            val sources = cells.count { it.type == "set-source" }
            val views = cells.count { it.type.endsWith("-view") }
            val operators = cells.size - sources - views
            sources shouldBeGreaterThanOrEqual 1
            operators shouldBeGreaterThanOrEqual 1
            views shouldBeGreaterThanOrEqual 2 // early + late

            // the op stream is present and drives declared sources
            val applies = s.script.filterIsInstance<ApplyStep>()
            applies.size shouldBeGreaterThanOrEqual 100
            applies.all { a -> cells.any { it.id == a.on && it.type == "set-source" } }.shouldBeTrue()

            // late joiner: a quiesce barrier followed by a mid-script connect
            s.script.any { it is QuiesceStep }.shouldBeTrue()
            s.script.any { it is ConnectStep }.shouldBeTrue()

            // the four standard property checks are realized against concrete views
            s.checks.any { it is IncrementalEqualsBatch && it.view == "*" }.shouldBeTrue()
            s.checks.any { it is ViewsConverge && it.views.size == 2 }.shouldBeTrue()
            s.checks.any { it is LateJoinEqualsEarly }.shouldBeTrue()
            s.checks.contains(NoDeadLetters).shouldBeTrue()
        }
    }

    @Test
    fun `the sweep exercises every operator in the vocabulary`() {
        val seen = mutableSetOf<String>()
        for (i in 0 until 80) {
            ScenarioGenerator.generate(spec, i).graph!!.cells.forEach { seen += it.type }
        }
        // every requested operator appears in at least one generated instance
        seen shouldContainAll listOf("filter", "map", "union", "intersect", "join", "group-by", "count")
    }

    @Test
    fun `generation is deterministic in the instance index`() {
        val a = ScenarioGenerator.generate(spec, 7)
        val b = ScenarioGenerator.generate(spec, 7)
        a.graph shouldBe b.graph
        a.script shouldBe b.script
        a.checks shouldBe b.checks
    }
}
