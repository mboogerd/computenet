package civictech.query.run

import civictech.cell.CellRef
import civictech.cell.data.SetOps
import civictech.cell.graph.lookup
import civictech.cell.link.LinkResult
import civictech.oracle.model.Membership
import civictech.oracle.model.ModelState
import civictech.oracle.model.Script
import civictech.oracle.model.ScriptEvent
import civictech.oracle.model.SourceId
import civictech.oracle.model.SourceScript
import civictech.oracle.model.WriterId
import civictech.oracle.run.SetTerminalFold
import civictech.query.lower.PlanFixtures
import civictech.query.ref.BatchEvaluator
import civictech.query.ref.RelationValue
import civictech.query.schema.Row
import civictech.testkit.SimWorld
import civictech.testkit.forEachSeed
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The named BS-* scenarios of computenet-cab.6.3, each pinned through the compiled graph:
 * BS-3 (preimage survival), BS-4 (join collapse), BS-8 (antijoin re-entry), BS-9 (group death
 * through the compiled aggregate), BS-10 (chain deletion on every seed) and BS-18 (late view
 * catch-up equals the early view equals the batch answer). BS-3's CONTROL half (a last-wins
 * lowering must MISMATCH) and the generator/mutation sweep belong to computenet-cab.6.4/.6.5,
 * not here (epic §5, cab.6-D3/D7).
 *
 * Every runner-driven scenario (all but BS-18, which has no barrier on the BYO path per
 * cab.6-D4/D12 and reads quiescent folds by hand instead) goes through [QueryCase.assertSuccess]
 * on every seed of `0L until 50L`: the hand-written script is fixed and the runner's own seed
 * varies only the injection interleaving and partial drains (`DifferentialRunner.kt:127-136`).
 *
 * **Non-vacuity (AMENDS computenet-cab.6.2, orchestrator task review):** every scenario asserts
 * `outputShapes.keys` equals its expected root name(s) — this proves the root is present in
 * [QueryCase.buildGraph]'s terminal map at all, guarding the AMENDS comment's original concern
 * (a root absent from `CompiledQuery.outputShapes` entirely). **It does NOT by itself prove the
 * terminal is actually linked to the live compiled output** — a task review (2026-09-14)
 * confirmed that mutating [QueryCase.buildGraph] to spawn a fold without ever calling
 * `connect` still passes this assertion, since `outputShapes` is a property of the compiled
 * plan alone. For a scenario whose script has a non-empty expected answer (BS-3, BS-4's
 * `survives`, BS-8, BS-9's `group2Dies`, BS-10, BS-18's early/late folds), [QueryCase
 * .assertSuccess]'s own content comparison catches an unlinked terminal as a collateral
 * effect — an unlinked [SetTerminalFold]/[civictech.oracle.run.MapTerminalFold] reads its
 * un-fed default (empty), which disagrees with a non-empty expectation. **A deliberately wrong
 * reference does NOT close this gap for an empty-answer script**: an unlinked terminal reads
 * empty regardless of what it is compared against, so it would `Mismatch` against any wrong
 * (non-empty) reference exactly as readily as a genuinely-linked, correctly-empty terminal
 * would — the comparison can't tell them apart. For a scenario whose deterministic script
 * always ends at the empty/absent state (BS-4's `collapses`, BS-9's `bothDie`), the fix instead
 * calls [QueryCase.buildGraph] directly — the SAME production linking code `assertSuccess`
 * uses, not a mock — drives the script by hand, and witnesses the terminal genuinely non-empty
 * mid-script before the final remove empties it, the way BS-18 proves `earlyFold` non-empty
 * before the late link.
 */
class QueryScenarioTest {

    private val f = PlanFixtures

    private fun row(vararg values: Any?) = Row(values.toList())

    /** Replays [events] onto a live source's ops — BS-18 drives the kernel by hand, not via the runner. */
    private fun replay(events: List<ScriptEvent>, ops: SetOps<Row>) {
        events.forEach { event ->
            when (event) {
                is ScriptEvent.Add -> ops.add(event.element as Row)
                is ScriptEvent.Remove -> ops.remove(event.element as Row)
                else -> error("QueryScenarioTest replay only handles Add/Remove, got $event")
            }
        }
    }

    @Test
    fun `BS-3 - a projection survives the removal of one of two colliding preimages`() {
        val catalog = f.catalog("r" to 2)
        val case = QueryCase.compile("q(X) :- r(X, Y).", catalog)
        withClue("non-vacuity: q is the plan's only root and the only compared terminal") {
            case.compiled.outputShapes.keys shouldBe setOf("q")
        }

        val script = Script(
            listOf(
                SourceScript(
                    SourceId("r"),
                    listOf(
                        ScriptEvent.Add(WriterId("r"), row(1, 1)),
                        ScriptEvent.Add(WriterId("r"), row(1, 2)),
                        ScriptEvent.Remove(WriterId("r"), row(1, 1)),
                    ),
                ),
            ),
        )
        forEachSeed(0L until 50L) { seed -> case.assertSuccess(seed, script) }

        withClue("[24-OP-FLATMAP-01]: the last live preimage (a,2) still projects X=1") {
            case.reference().evaluate(script).getValue("q") shouldBe ModelState.SetState(setOf(row(1)))
        }
    }

    @Test
    fun `BS-4 - many-to-one join collapse under retraction`() {
        val catalog = f.catalog("r" to 2, "s" to 1)
        val case = QueryCase.compile("q(C) :- r(K, C), s(K).", catalog)
        withClue("non-vacuity: q is the plan's only root and the only compared terminal") {
            case.compiled.outputShapes.keys shouldBe setOf("q")
        }

        fun script(vararg rRemoves: Row) = Script(
            listOf(
                SourceScript(
                    SourceId("r"),
                    listOf(
                        ScriptEvent.Add(WriterId("r"), row(1, 7)),
                        ScriptEvent.Add(WriterId("r"), row(2, 7)),
                    ) + rRemoves.map { ScriptEvent.Remove(WriterId("r"), it) },
                ),
                SourceScript(
                    SourceId("s"),
                    listOf(
                        ScriptEvent.Add(WriterId("s"), row(1)),
                        ScriptEvent.Add(WriterId("s"), row(2)),
                    ),
                ),
            ),
        )

        val survives = script(row(1, 7))
        forEachSeed(0L until 50L) { seed -> case.assertSuccess(seed, survives) }
        withClue("[24-OP-JOINSET-02]: c=7 survives via the still-live pair (2,7)") {
            case.reference().evaluate(survives).getValue("q") shouldBe ModelState.SetState(setOf(row(7)))
        }

        val collapses = script(row(1, 7), row(2, 7))
        forEachSeed(0L until 50L) { seed -> case.assertSuccess(seed, collapses) }
        withClue("[24-OP-JOINSET-02]: c=7's last contributing pair died") {
            case.reference().evaluate(collapses).getValue("q") shouldBe ModelState.EMPTY_SET
        }

        // NON-VACUITY WITNESS: collapses' expected answer is always EMPTY_SET, so an unlinked
        // terminal's un-fed default would coincidentally match `assertSuccess` above — a wrong
        // reference control cannot catch this either, since an unlinked terminal reads empty
        // regardless of what it is compared against. Drive [QueryCase.buildGraph] directly (the
        // SAME production linking code assertSuccess uses, not a mock) and observe the terminal
        // is genuinely non-empty mid-script, before the final remove collapses it to empty.
        val witnessWorld = SimWorld(seed = 0)
        val witnessGraph = case.buildGraph(witnessWorld)
        val qFold = witnessGraph.terminals.getValue("q")
        val rSource = witnessGraph.sources.getValue(SourceId("r"))
        val sSource = witnessGraph.sources.getValue(SourceId("s"))
        rSource.add(row(1, 7))
        rSource.add(row(2, 7))
        sSource.add(row(1))
        sSource.add(row(2))
        rSource.remove(row(1, 7))
        witnessWorld.runToIdle()
        withClue("[24-OP-JOINSET-02] non-vacuity: q must be genuinely non-empty before the final remove") {
            (qFold.current() as ModelState.SetState).elements.isEmpty() shouldBe false
        }
        rSource.remove(row(2, 7))
        witnessWorld.runToIdle()
        withClue("[24-OP-JOINSET-02]: c=7's last contributing pair died, on the SAME linked terminal just witnessed non-empty") {
            qFold.current() shouldBe ModelState.SetState(emptySet())
        }
    }

    @Test
    fun `BS-8 - antijoin re-entry after the witness's removal is live at idle`() {
        val catalog = f.catalog("r" to 1, "s" to 1)
        val case = QueryCase.compile("q(X) :- r(X), not s(X).", catalog)
        withClue("non-vacuity: q is the plan's only root and the only compared terminal") {
            case.compiled.outputShapes.keys shouldBe setOf("q")
        }

        val script = Script(
            listOf(
                SourceScript(SourceId("r"), listOf(ScriptEvent.Add(WriterId("r"), row(1)))),
                SourceScript(
                    SourceId("s"),
                    listOf(
                        ScriptEvent.Add(WriterId("s"), row(1)),
                        ScriptEvent.Remove(WriterId("s"), row(1)),
                    ),
                ),
            ),
        )
        forEachSeed(0L until 50L) { seed -> case.assertSuccess(seed, script) }
        withClue("[24-OP-SEMIJOIN-02]: x re-enters under a fresh mint once its witness is gone") {
            case.reference().evaluate(script).getValue("q") shouldBe ModelState.SetState(setOf(row(1)))
        }

        var totals = QueryScripts.ScriptStats.ZERO
        forEachSeed(0L until 50L) { seed ->
            val scripts = QueryScripts(seed, catalog, steps = 40, deletionRatio = 0.5)
            totals += scripts.stats
            case.assertSuccess(seed, scripts.script)
        }
        withClue("achieved over the seeded BS-8 sweep: $totals") {
            totals.ops shouldBeGreaterThan 0
            totals.removes shouldBeGreaterThan 0
        }
    }

    @Test
    fun `BS-9 - group death through the compiled aggregate`() {
        val catalog = f.catalog("r" to 2)
        val case = QueryCase.compile("@count byKey(K, V) :- r(K, V).", catalog)
        withClue("non-vacuity: byKey is the plan's only root and the only compared terminal") {
            case.compiled.outputShapes.keys shouldBe setOf("byKey")
        }
        case.compiled.outputShapes.getValue("byKey") shouldBe OutputShape.MAP_BY_GROUP

        fun script(removeGroup1: Boolean) = Script(
            listOf(
                SourceScript(
                    SourceId("r"),
                    buildList {
                        add(ScriptEvent.Add(WriterId("r"), row(1, 10)))
                        add(ScriptEvent.Add(WriterId("r"), row(1, 11)))
                        add(ScriptEvent.Add(WriterId("r"), row(2, 20)))
                        add(ScriptEvent.Remove(WriterId("r"), row(2, 20)))
                        if (removeGroup1) {
                            add(ScriptEvent.Remove(WriterId("r"), row(1, 10)))
                            add(ScriptEvent.Remove(WriterId("r"), row(1, 11)))
                        }
                    },
                ),
            ),
        )

        val group2Dies = script(removeGroup1 = false)
        forEachSeed(0L until 50L) { seed -> case.assertSuccess(seed, group2Dies) }
        val afterGroup2 = (case.reference().evaluate(group2Dies).getValue("byKey") as ModelState.MapState).entries
        withClue("[24-OP-GROUPBY-02]: group 2 is absent as soon as its only row dies; group 1 survives live") {
            afterGroup2.keys shouldBe setOf(row(1))
            afterGroup2.getValue(row(1)) shouldBe 2L
        }

        val bothDie = script(removeGroup1 = true)
        forEachSeed(0L until 50L) { seed -> case.assertSuccess(seed, bothDie) }
        val afterBoth = (case.reference().evaluate(bothDie).getValue("byKey") as ModelState.MapState).entries
        withClue("[24-OP-GROUPBY-02]: an emptied group is absent, never present with an identity value") {
            afterBoth.keys shouldBe emptySet()
        }

        // NON-VACUITY WITNESS: bothDie's expected answer is always the empty map, so an unlinked
        // terminal's un-fed default would coincidentally match `assertSuccess` above — a wrong
        // reference control cannot catch this either, since an unlinked terminal reads empty
        // regardless of what it is compared against. Drive [QueryCase.buildGraph] directly (the
        // SAME production linking code assertSuccess uses, not a mock) and observe the terminal
        // genuinely holds group 1 mid-script, before its own final removal empties it too.
        val witnessWorld = SimWorld(seed = 0)
        val witnessGraph = case.buildGraph(witnessWorld)
        val byKeyFold = witnessGraph.terminals.getValue("byKey")
        val rSource = witnessGraph.sources.getValue(SourceId("r"))
        rSource.add(row(1, 10))
        rSource.add(row(1, 11))
        rSource.add(row(2, 20))
        rSource.remove(row(2, 20))
        witnessWorld.runToIdle()
        withClue("[24-OP-GROUPBY-02] non-vacuity: byKey must genuinely hold group 1 before it too is removed") {
            (byKeyFold.current() as ModelState.MapState).entries.keys shouldBe setOf(row(1))
        }
        rSource.remove(row(1, 10))
        rSource.remove(row(1, 11))
        witnessWorld.runToIdle()
        withClue("[24-OP-GROUPBY-02]: an emptied group is absent, on the SAME linked terminal just witnessed holding it") {
            (byKeyFold.current() as ModelState.MapState).entries.keys shouldBe emptySet()
        }
    }

    @Test
    fun `BS-10 - deletion through a three-relation chain on every seed`() {
        val catalog = f.catalog("r" to 2, "s" to 2, "t" to 1)
        val case = QueryCase.compile("q(A, C) :- r(A, B), s(B, C), t(C).", catalog)
        withClue("non-vacuity: q is the plan's only root and the only compared terminal") {
            case.compiled.outputShapes.keys shouldBe setOf("q")
        }

        var totals = QueryScripts.ScriptStats.ZERO
        var middleDeletionWitnessed = false
        forEachSeed(0L until 50L) { seed ->
            val scripts = QueryScripts(seed, catalog, steps = 60, deletionRatio = 0.4)
            totals += scripts.stats
            case.assertSuccess(seed, scripts.script)

            if (!middleDeletionWitnessed && scripts.stats.removedRowsPerRelation.getValue("s") > 0) {
                val finalDb = catalog.relations.keys.associateWith { relation ->
                    Membership.live(scripts.script.slice(SourceId(relation))).mapTo(LinkedHashSet()) { it as Row }
                }
                val everAddedToS = scripts.script.slice(SourceId("s")).events
                    .filterIsInstance<ScriptEvent.Add>()
                    .mapTo(LinkedHashSet()) { it.element as Row }
                val restoredDb: Map<String, Set<Row>> = finalDb + ("s" to everAddedToS)

                val finalAnswer =
                    (BatchEvaluator.evaluate(case.compiled.plan, finalDb).getValue("q") as RelationValue.Rows).rows
                val restoredAnswer =
                    (BatchEvaluator.evaluate(case.compiled.plan, restoredDb).getValue("q") as RelationValue.Rows).rows
                if (finalAnswer.isNotEmpty() && restoredAnswer != finalAnswer) middleDeletionWitnessed = true
            }
        }
        withClue("achieved over the sweep: $totals — removes must be at least 30% of ops [QRY1-ORA-06]") {
            (totals.removes * 10) shouldBeGreaterThanOrEqual totals.ops * 3
        }
        withClue(
            "[QRY1-ORA-06][QRY1-SEM-08]: at least one seed must delete a middle-relation (s) row " +
                "that changes q's final, non-empty answer",
        ) {
            middleDeletionWitnessed shouldBe true
        }
    }

    @Test
    fun `BS-18 - a late view equals the early view equals the batch answer`() {
        val catalog = f.catalog("r" to 2, "t" to 2)
        val case = QueryCase.compile("j(X, Z) :- r(X, Y), t(Y, Z).", catalog)
        withClue("non-vacuity: j is the plan's only root and the only compared terminal") {
            case.compiled.outputShapes.keys shouldBe setOf("j")
        }
        val compiled = case.compiled

        forEachSeed(0L until 20L) { seed ->
            val world = SimWorld(seed = seed)
            val applied = compiled.applyTo(world.host.managementInlet)
            val rOps = world.host.lookup(applied.sources.getValue("r"))!!.inlet.call
            val tOps = world.host.lookup(applied.sources.getValue("t"))!!.inlet.call
            val outputRef = applied.outputs.getValue("j").ref

            // A linked fold, not a raw `outlet.subscribe` — only a real link fires the
            // `catchUpOnLinked` hook ([24-CATCHUP-01]); `FanOutlet.subscribe` is a bare attach
            // that `JoinSetCell`'s catch-up never sees (kernel/.../port/FanOutlet.kt:525-529).
            val earlyFold = linkedFold(world, outputRef)

            // A fixed first half guarantees j is non-empty before the late fold links, so its
            // catch-up below is provably non-vacuous rather than an empty no-op.
            val fixedR = listOf(ScriptEvent.Add(WriterId("r"), row(1, 10)))
            val fixedT = listOf(ScriptEvent.Add(WriterId("t"), row(10, 100)))
            replay(fixedR, rOps)
            replay(fixedT, tOps)

            val generated = QueryScripts(seed, catalog, steps = 30, deletionRatio = 0.4)
            val genR = generated.script.slice(SourceId("r")).events
            val genT = generated.script.slice(SourceId("t")).events
            val halfR = genR.size / 2
            val halfT = genT.size / 2
            replay(genR.subList(0, halfR), rOps)
            replay(genT.subList(0, halfT), tOps)
            world.runToIdle()

            withClue("seed=$seed: j must be non-empty before the late fold links") {
                (earlyFold.current() as ModelState.SetState).elements.isEmpty() shouldBe false
            }

            val lateFold = linkedFold(world, outputRef)
            world.runToIdle()
            withClue("seed=$seed: [24-CATCHUP-01] the late fold must already agree with the early one before the second half is written") {
                lateFold.current() shouldBe earlyFold.current()
            }

            replay(genR.subList(halfR, genR.size), rOps)
            replay(genT.subList(halfT, genT.size), tOps)
            world.runToIdle()

            val finalR = Membership.live(SourceScript(SourceId("r"), fixedR + genR)).mapTo(LinkedHashSet()) { it as Row }
            val finalT = Membership.live(SourceScript(SourceId("t"), fixedT + genT)).mapTo(LinkedHashSet()) { it as Row }
            val expected = (
                BatchEvaluator.evaluate(compiled.plan, mapOf("r" to finalR, "t" to finalT)).getValue("j")
                    as RelationValue.Rows
                ).rows

            withClue("seed=$seed") {
                earlyFold.current() shouldBe ModelState.SetState(expected)
                lateFold.current() shouldBe ModelState.SetState(expected)
            }
        }
    }

    /**
     * Spawns a [SetTerminalFold] and links it to [outputRef]'s outlet through the management
     * inlet — the same `connect` path [QueryCase.buildGraph] uses for a runner terminal, and
     * the one that actually fires late-join catch-up (`kernel/.../link/CatchUp.kt`).
     */
    private fun linkedFold(world: SimWorld, outputRef: CellRef): SetTerminalFold<Row> {
        val fold = SetTerminalFold<Row>()
        world.host.managementInlet.call.spawn(fold)
        val result = world.host.managementInlet.call.connect(outputRef, "outlet", fold.ref, "inlet")
        check(result !is LinkResult.Rejected) {
            "linking the BS-18 fold to '$outputRef' was rejected: ${(result as LinkResult.Rejected).reason}"
        }
        return fold
    }
}
