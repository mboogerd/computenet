package civictech.query.run

import civictech.oracle.model.ModelState
import civictech.oracle.model.Script
import civictech.oracle.model.ScriptEvent
import civictech.oracle.model.SourceId
import civictech.oracle.model.SourceScript
import civictech.oracle.model.WriterId
import civictech.oracle.run.RunOutcome
import civictech.oracle.run.StateDifference
import civictech.query.lower.Lowering
import civictech.query.lower.LoweringResult
import civictech.query.lower.PlanFixtures
import civictech.query.run.controls.StatelessLastWinsProjectCell
import civictech.query.run.controls.lastWinsProjection
import civictech.query.run.controls.statelessLastWinsProjection
import civictech.query.run.controls.stickyGroupBy
import civictech.query.schema.AttrType
import civictech.query.schema.Attribute
import civictech.query.schema.Catalog
import civictech.query.schema.RelationSchema
import civictech.query.schema.Row
import civictech.testkit.forEachSeed
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * The divergence control and the mutation check of computenet-cab.6.4 (`[QRY1-ORA-07]`,
 * `[QRY1-ORA-08]`; BS-3's control half, BS-16): a deliberately wrong test-scope cell is
 * substituted into ONE spawn of a compiled spec (`civictech.query.run.controls`, cab.6-D7) and
 * the differential runner must catch it — while the unmutated spec, on the same scripts and
 * seeds, succeeds. That pairing is what makes each test non-vacuous: a `Mismatch` the correct
 * lowering also produced would prove nothing, and a `Success` the wrong cell also produced
 * would prove the sweep cannot fail.
 *
 * Every case additionally asserts that each plan root has a compared terminal (the
 * computenet-cab.6.2 AMENDS: the runner compares only the terminals a `CaseGraph` names).
 */
class DivergenceControlTest {

    private fun row(vararg values: Any?) = Row(values.toList())

    private fun add(relation: String, row: Row) = ScriptEvent.Add(WriterId(relation), row)

    private fun remove(relation: String, row: Row) = ScriptEvent.Remove(WriterId(relation), row)

    private fun script(relation: String, vararg events: ScriptEvent) =
        Script(listOf(SourceScript(SourceId(relation), events.toList())))

    private fun assertEveryRootCompared(case: QueryCase) {
        withClue("non-vacuity: every plan root of ${case.source} must have a compared terminal") {
            case.compiled.outputShapes.keys shouldBe case.compiled.plan.roots.keys
        }
    }

    @Test
    fun `QRY1 §ORA-07 BS-3 control - the last-wins projection is caught on at least one seed of 0 until 50`() {
        val case = QueryCase.compile(PROJECTION_SOURCE, PROJECTION_CATALOG)
        val mutated = case.copy(compiled = case.compiled.lastWinsProjection("q"))
        assertEveryRootCompared(case)
        mutated.compiled.spec shouldNotBe case.compiled.spec

        // (1, 1L) and (1, 2L) both project onto X = 1; the owner (1, 2L) dies while (1, 1L) is
        // live. The prescribed `add(1,1) add(1,2) remove(1,1)` removes the DISPLACED preimage
        // instead, which no last-wins variant diverges on; see LastWinsProjectCell's KDoc.
        val hand = script("r", add("r", row(1, 1L)), add("r", row(1, 2L)), remove("r", row(1, 2L)))

        val mismatchSeeds = sortedSetOf<String>()
        var totals = QueryScripts.ScriptStats.ZERO
        forEachSeed(SEEDS) { seed ->
            val generated = QueryScripts(seed, PROJECTION_CATALOG, PROJECTION_DOMAIN, steps = 40, deletionRatio = 0.5)
            totals += generated.stats
            listOf("hand" to hand, "generated" to generated.script).forEach { (label, script) ->
                case.assertSuccess(seed, script)
                when (val outcome = mutated.check(seed, script)) {
                    RunOutcome.Success -> Unit
                    else -> {
                        withClue("seed=$seed $label: the control may only fail as a mismatch on q\n${outcome.describe(mutated, script)}") {
                            val mismatch = outcome.shouldBeInstanceOf<RunOutcome.Mismatch>()
                            mismatch.terminal shouldBe "q"
                        }
                        mismatchSeeds += "$label:$seed"
                    }
                }
            }
        }
        val generatedFailures = mismatchSeeds.count { it.startsWith("generated:") }
        println(
            "[QRY1-ORA-07] last-wins control: ${mismatchSeeds.size} failing (script, seed) pairs of ${2 * SEEDS.count()}; " +
                "generated sweep failed on $generatedFailures of ${SEEDS.count()} seeds; achieved $totals",
        )
        withClue("[QRY1-ORA-07] the last-wins control was caught on no seed of $SEEDS — a finding, not a range to widen") {
            mismatchSeeds.shouldNotBeEmpty()
        }
        withClue("the generated sweep alone must catch the control somewhere in $SEEDS") {
            (generatedFailures > 0) shouldBe true
        }
    }

    @Test
    fun `computenet-cab 6 6 - the stateless per-delta last-wins remap is caught downstream of a join, and only there`() {
        // Premise check first: over the plain projection (a SetCell source, one row per delta)
        // the stateless remap sees no in-delta collision and no seed catches it.
        val plain = QueryCase.compile(PROJECTION_SOURCE, PROJECTION_CATALOG)
        val plainMutated = plain.copy(compiled = plain.compiled.statelessLastWinsProjection("q"))
        StatelessLastWinsProjectCell.collidingDeltas.set(0)
        var plainMismatches = 0
        forEachSeed(SEEDS) { seed ->
            val generated = QueryScripts(seed, PROJECTION_CATALOG, PROJECTION_DOMAIN, steps = 40, deletionRatio = 0.5)
            if (plainMutated.check(seed, generated.script) != RunOutcome.Success) plainMismatches++
        }
        val plainCollisions = StatelessLastWinsProjectCell.collidingDeltas.get()

        val case = QueryCase.compile(JOIN_PROJECTION_SOURCE, JOIN_CATALOG)
        val mutated = case.copy(compiled = case.compiled.statelessLastWinsProjection("q"))
        assertEveryRootCompared(case)
        mutated.compiled.spec shouldNotBe case.compiled.spec

        // r(1, 1) meets s(1, 1) and s(1, 2): whichever side arrives last, if it is r(1, 1) the
        // join emits (1, 1, 1) and (1, 1, 2) in ONE delta, both projecting onto q(1); the remap
        // keeps one tag. Removing the kept pair's s row then kills q(1) while (1, 1, other) is
        // live. Which pair is kept is the join's map order, so both removals are scripted.
        fun hand(removed: Int) = Script(
            listOf(
                SourceScript(SourceId("r"), listOf(add("r", row(1, 1)))),
                SourceScript(SourceId("s"), listOf(add("s", row(1, 1)), add("s", row(1, 2)), remove("s", row(1, removed)))),
            ),
        )

        StatelessLastWinsProjectCell.collidingDeltas.set(0)
        val mismatchSeeds = sortedSetOf<String>()
        var totals = QueryScripts.ScriptStats.ZERO
        forEachSeed(SEEDS) { seed ->
            val generated = QueryScripts(seed, JOIN_CATALOG, JOIN_DOMAIN, steps = 40, deletionRatio = 0.5)
            totals += generated.stats
            listOf("hand1" to hand(1), "hand2" to hand(2), "generated" to generated.script).forEach { (label, script) ->
                case.assertSuccess(seed, script)
                when (val outcome = mutated.check(seed, script)) {
                    RunOutcome.Success -> Unit
                    else -> {
                        withClue("seed=$seed $label: the control may only fail as a mismatch on q\n${outcome.describe(mutated, script)}") {
                            val mismatch = outcome.shouldBeInstanceOf<RunOutcome.Mismatch>()
                            mismatch.terminal shouldBe "q"
                        }
                        mismatchSeeds += "$label:$seed"
                    }
                }
            }
        }
        val joinCollisions = StatelessLastWinsProjectCell.collidingDeltas.get()
        val generatedFailures = mismatchSeeds.count { it.startsWith("generated:") }
        println(
            "[cab.6.6] stateless last-wins: plain projection $plainMismatches of ${SEEDS.count()} seeds caught, " +
                "$plainCollisions colliding deltas; downstream of join ${mismatchSeeds.size} failing (script, seed) pairs of " +
                "${3 * SEEDS.count()}, generated sweep failed on $generatedFailures of ${SEEDS.count()} seeds, " +
                "$joinCollisions colliding deltas; achieved $totals",
        )
        withClue("premise: a SetCell-fed projection never sees two colliding preimages in one delta") {
            plainCollisions shouldBe 0L
            plainMismatches shouldBe 0
        }
        withClue("premise: the join emitted at least one delta with two rows colliding under the projection") {
            (joinCollisions > 0L) shouldBe true
        }
        withClue("the stateless remap downstream of a join was caught on no seed of $SEEDS — a finding, not a range to widen") {
            mismatchSeeds.shouldNotBeEmpty()
        }
    }

    @Test
    fun `QRY1 §ORA-08 BS-16 a sticky group-by is caught and attributed to the aggregate root`() {
        val catalog = PlanFixtures.catalog("r" to 2)
        val case = QueryCase.compile("a(X) :- r(X, Y).\n@count z(X, Y) :- r(X, Y).", catalog)
        val mutated = case.copy(compiled = case.compiled.stickyGroupBy("z"))
        val setOnly = QueryCase.compile("a(X) :- r(X, Y).", catalog)
        assertEveryRootCompared(case)
        assertEveryRootCompared(setOnly)
        withClue("attribution is only meaningful if the correct root is compared FIRST") {
            case.compiled.outputShapes.keys.toList() shouldBe listOf("a", "z")
        }

        // Group 1 gains two rows and loses both; group 2 keeps its one row.
        val emptiesGroupOne = Script(
            listOf(
                SourceScript(
                    SourceId("r"),
                    listOf(
                        add("r", row(1, 10)),
                        add("r", row(1, 11)),
                        add("r", row(2, 20)),
                        remove("r", row(1, 10)),
                        remove("r", row(1, 11)),
                    ),
                ),
            ),
        )

        forEachSeed(0L until 20L) { seed ->
            case.assertSuccess(seed, emptiesGroupOne)
            setOnly.assertSuccess(seed, emptiesGroupOne)

            val outcome = mutated.check(seed, emptiesGroupOne)
            withClue("seed=$seed\n${outcome.describe(mutated, emptiesGroupOne)}") {
                val mismatch = outcome.shouldBeInstanceOf<RunOutcome.Mismatch>()
                mismatch.terminal shouldBe "z"
                mismatch.expected shouldBe ModelState.MapState(mapOf(row(2) to 1L))
                val difference = mismatch.difference.shouldBeInstanceOf<StateDifference.MapDifference>()
                difference.onlyInActual.keys shouldContain row(1)
                difference.onlyInActual shouldBe mapOf(row(1) to 0L)
                difference.onlyInExpected shouldBe emptyMap()
            }
        }
    }

    @Test
    fun `both controls leave the unmutated lowering untouched`() {
        val catalog = PlanFixtures.catalog("r" to 2)
        val projection = QueryCase.compile(PROJECTION_SOURCE, PROJECTION_CATALOG)
        val grouped = QueryCase.compile("a(X) :- r(X, Y).\n@count z(X, Y) :- r(X, Y).", catalog)

        val projectionMutant = projection.compiled.lastWinsProjection("q")
        val groupedMutant = grouped.compiled.stickyGroupBy("z")

        fun fresh(case: QueryCase) =
            Lowering.lower(case.compiled.plan, case.catalog).shouldBeInstanceOf<LoweringResult.Lowered>().spec
        projection.compiled.spec shouldBe fresh(projection)
        grouped.compiled.spec shouldBe fresh(grouped)
        projectionMutant.spec shouldNotBe projection.compiled.spec
        groupedMutant.spec shouldNotBe grouped.compiled.spec
        projectionMutant.copy(spec = projection.compiled.spec) shouldBe projection.compiled
        groupedMutant.copy(spec = grouped.compiled.spec) shouldBe grouped.compiled
    }

    internal companion object {
        /** Fixed seed range for the BS-3 control; never widened or reseeded to make it pass. */
        val SEEDS = 0L until 50L

        const val PROJECTION_SOURCE = "q(X) :- r(X, Y)."

        /** `a0` INT, `a1` LONG, so the domain can give X two values and Y six: projections collide heavily. */
        val PROJECTION_CATALOG = Catalog(
            mapOf("r" to RelationSchema(listOf(Attribute("a0", AttrType.INT), Attribute("a1", AttrType.LONG)))),
        )

        /** A projection over a join's output: one r row meeting two s rows yields two q(X) preimages in one delta. */
        const val JOIN_PROJECTION_SOURCE = "q(X) :- r(X, Y), s(Y, Z)."

        val JOIN_CATALOG: Catalog = PlanFixtures.catalog("r" to 2, "s" to 2)

        /** Three INT values: joins match often and projections onto X collide often. */
        val JOIN_DOMAIN: Map<AttrType, List<Any>> = QueryScripts.DEFAULT_DOMAIN + mapOf(AttrType.INT to listOf(1, 2, 3))

        val PROJECTION_DOMAIN: Map<AttrType, List<Any>> =
            QueryScripts.DEFAULT_DOMAIN + mapOf(AttrType.INT to listOf(1, 2), AttrType.LONG to (1L..6L).toList())
    }
}
