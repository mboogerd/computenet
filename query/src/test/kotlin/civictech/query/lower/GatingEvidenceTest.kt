package civictech.query.lower

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.SetApi
import civictech.cell.data.SetOps
import civictech.cell.data.op.SemiJoinCell
import civictech.cell.data.view.SetView
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.SpawnStep
import civictech.cell.graph.TypedCellFactory
import civictech.cell.graph.TypedRef
import civictech.cell.graph.lookup
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.query.parse.ParseResult
import civictech.query.parse.QueryParser
import civictech.query.plan.Planner
import civictech.query.run.AppliedQuery
import civictech.query.run.CompiledQuery
import civictech.query.schema.Catalog
import civictech.query.schema.Row
import civictech.testkit.SimWorld
import civictech.testkit.forEachSeed
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * `emitOnFrontier` gating evidence on a live host (computenet-cab.4.5): what [Gating.decide]'s
 * rule (cab.4-D3, cab.4-D6) DOES once a compiled query is applied to a [SimWorld], not only
 * what it decides on a hand-built plan (`LoweringStructureTest`'s job).
 *
 * Every live test compiles through the production path — `QueryParser.parse` →
 * [Planner.plan] → [Lowering.lower] → [CompiledQuery.from] → [CompiledQuery.applyTo] — drives a
 * seeded script of edge adds and removes over [SEEDS], `runToIdle`s after each step, and
 * compares the root's materialized answer with a test-local batch fold computed over the
 * CURRENT relation contents.
 *
 * **How the gated cell is read.** `SemiJoinCell.bufferedWaves` is a plain property, not a port;
 * the host's `lookup` hands back a port-routing proxy that cannot read it, and `ManagedHost`
 * exposes no raw cell. So [withCapturedAntijoin] copies the compiled spec with the one antijoin
 * [SpawnStep]'s factory wrapped in [CapturingFactory], which calls the lowered
 * [SemiJoinFactory]'s own `create` unchanged and records the instance it returns. The cell under
 * test is therefore exactly the cell the lowering configured; only a reference to it is kept.
 */
class GatingEvidenceTest {

    // ------------------------------------------------------------------------------ BS-6 gated

    /**
     * BS-6's gated half (`[QRY1-LOWER-08]`): the self-join diamond with a downstream antijoin
     * over the same relation. Both arms are at most one operator from `src:e` (the join feeds
     * the antijoin's left inlet directly; `src:e` feeds its right), so the lowering gates it —
     * and on a live host the gated answer equals the batch fold at every idle point on every
     * seed, with nothing held on the gate.
     */
    @Test
    fun `BS-6 gated - the self-join diamond antijoin is gated, agrees with the batch fold at every idle point, and withholds nothing`() {
        val catalog = PlanFixtures.catalog("e" to 2)
        val compiled = compile("q(X, Z) :- e(X, Y), e(Y, Z), not e(X, Z).", catalog)
        val (antijoinHandle, factory) = antijoinOf(compiled)
        factory.emitOnFrontier shouldBe true
        compiled.diagnostics.filter { it.handle == antijoinHandle } shouldBe emptyList()

        val totals = OpTally()
        forEachSeed(SEEDS) { seed ->
            val world = SimWorld(seed = seed)
            val (applied, gated) = withCapturedAntijoin(compiled, antijoinHandle, world)
            val e = writerOf(world, applied, "e")
            val q = viewOf(world, applied, "q")
            val edges = mutableSetOf<Row>()

            val script = Script(Random(seed), domain = 1..4)
            repeat(STEPS) { step ->
                script.step(edges, e, totals)
                world.runToIdle()
                withClue("seed=$seed step=$step edges=$edges") {
                    gated.bufferedWaves shouldBe 0
                    q.current() shouldBe totals.idle(selfJoinMinusE(edges, filter = { true }))
                }
            }
        }
        totals.assertRemovalRatio()
    }

    // ---------------------------------------------------------------- independent roots

    /**
     * `[QRY1-LOWER-09]`, `[QRY1-HONEST-04]`: the witness `t` shares no source with the join
     * over `e`, so there is no common wave frontier; the antijoin runs ungated and the lowering
     * says so with an [LoweringDiagnostic.EventuallyConsistent] naming it.
     *
     * THIS TEST ASSERTS IDLE-POINT AGREEMENT ONLY. It deliberately makes NO assertion about
     * intermediate emissions — no glitch-freedom, no flicker-freedom, no emission count — because
     * the independent-root case is specified as eventually consistent ([QRY1-HONEST-04]) and no
     * test may claim more. Do not "strengthen" it.
     */
    @Test
    fun `independent roots - the antijoin is ungated with an EventuallyConsistent diagnostic, and converges to the batch fold at idle`() {
        val catalog = PlanFixtures.catalog("e" to 2, "t" to 2)
        val compiled = compile("q(X, Z) :- e(X, Y), e(Y, Z), not t(X, Z).", catalog)
        val (antijoinHandle, factory) = antijoinOf(compiled)
        factory.emitOnFrontier shouldBe false
        val diagnostic = compiled.diagnostics.single { it.handle == antijoinHandle }
        diagnostic.shouldBeInstanceOf<LoweringDiagnostic.EventuallyConsistent>()

        val totals = OpTally()
        forEachSeed(SEEDS) { seed ->
            val world = SimWorld(seed = seed)
            val applied = compiled.applyTo(world.host.managementInlet)
            val e = writerOf(world, applied, "e")
            val t = writerOf(world, applied, "t")
            val q = viewOf(world, applied, "q")
            val edges = mutableSetOf<Row>()
            val blocked = mutableSetOf<Row>()

            val random = Random(seed)
            val eScript = Script(random, domain = 1..4)
            val tScript = Script(random, domain = 1..4)
            repeat(STEPS) { step ->
                // Both relations move in the same step, so their waves interleave before idle.
                eScript.step(edges, e, totals)
                tScript.step(blocked, t, totals)
                world.runToIdle()
                withClue("seed=$seed step=$step e=$edges t=$blocked") {
                    q.current() shouldBe totals.idle(selfJoinMinus(edges, blocked, filter = { true }))
                }
            }
        }
        totals.assertRemovalRatio()
    }

    // ----------------------------------------------------------------------- depth rule

    /**
     * cab.4-D6, epic risk 3 (fail closed): the pushed comparison `Y > 0` puts a `FilterCell`
     * under the join, so the antijoin's left arm is two operators deep. The arms still share
     * `e`, but the carry precondition is not provable, so the lowering leaves the antijoin
     * ungated with a [LoweringDiagnostic.GateNotProvable] citing F-15 — and the ungated cell
     * converges to the batch fold at every idle point. (Idle-point agreement only; an ungated
     * antijoin may flicker in flight.)
     */
    @Test
    fun `depth rule - a two-operator-deep arm leaves the antijoin ungated with GateNotProvable naming F-15, and it converges at idle`() {
        val catalog = PlanFixtures.catalog("e" to 2)
        val compiled = compile(DEEP_ARM_QUERY, catalog)
        val (antijoinHandle, factory) = antijoinOf(compiled)
        factory.emitOnFrontier shouldBe false
        val diagnostic = compiled.diagnostics.single { it.handle == antijoinHandle }
        diagnostic.shouldBeInstanceOf<LoweringDiagnostic.GateNotProvable>().reason shouldContain "F-15"

        val totals = OpTally()
        forEachSeed(SEEDS) { seed ->
            val world = SimWorld(seed = seed)
            val applied = compiled.applyTo(world.host.managementInlet)
            val e = writerOf(world, applied, "e")
            val q = viewOf(world, applied, "q")
            val edges = mutableSetOf<Row>()

            val script = Script(Random(seed), domain = -2..3)
            repeat(STEPS) { step ->
                script.step(edges, e, totals)
                world.runToIdle()
                withClue("seed=$seed step=$step edges=$edges") {
                    q.current() shouldBe totals.idle(selfJoinMinusE(edges, filter = { y -> y > 0 }))
                }
            }
        }
        totals.assertRemovalRatio()
    }

    // ------------------------------------------------------------------------- F-15 pin

    /**
     * The F-15 pin, prescribed shape: [DEEP_ARM_QUERY]'s plan (the one the depth rule refuses to
     * gate) applied with the antijoin's gate FORCED on in test scope — the lowered spec copied
     * with that one [SemiJoinFactory] replaced by `copy(emitOnFrontier = true)`; no production
     * flag. The script mirrors the reproducing case below: build a candidate answer, block it
     * with a witness edge whose `Y <= 0` the pushed filter drops, and END on removing that edge —
     * the wave that re-admits the answer and that the filtered arm structurally drops.
     *
     * RESULT (measured 2026-09-14, seeds 0..49): this does NOT reproduce the withholding. The gate settles with nothing buffered and
     * the answer equals the batch fold. Why, in this compiled shape: `src:e` fans into BOTH join
     * inlets (a self-join), so a wave the filter drops on the join's left inlet still reaches the
     * join's right inlet directly, and the join either emits or absorb-acks onto the antijoin's
     * left inlet — the join is the absorber and it links straight into the gated edge, which is
     * F-15's safe case. A two-operator-deep arm is therefore NOT sufficient for withholding; the
     * silent arm must have no other path from the root. `F-15 reproduces on a compiled shape`
     * below shows such a shape does withhold, so this negative does not license widening the
     * depth rule (no production rule change here, per the task).
     */
    @Test
    fun `F-15 negative - forcing the gate on the self-join depth shape does not withhold, because src e reaches the join directly on its other inlet`() {
        val catalog = PlanFixtures.catalog("e" to 2)
        val compiled = compile(DEEP_ARM_QUERY, catalog)
        val (antijoinHandle, _) = antijoinOf(compiled)

        forEachSeed(SEEDS) { seed ->
            val world = SimWorld(seed = seed)
            val (applied, gated) = withCapturedAntijoin(compiled, antijoinHandle, world, forceGate = true)
            val e = writerOf(world, applied, "e")
            val q = viewOf(world, applied, "q")

            // The same script shape as the reproducing case below: build a candidate answer,
            // block it with a witness edge the filter drops, then end on removing that edge —
            // the wave that RE-ADMITS the answer and that the filtered arm structurally drops.
            e.add(row(1, 2)); world.runToIdle() // Y = 2 passes the filter
            e.add(row(2, -1)); world.runToIdle() // joins: candidate answer (1, -1)
            e.add(row(1, -1)); world.runToIdle() // witness blocks (1, -1); the filter drops it
            e.remove(row(1, -1)); world.runToIdle() // LAST wave: re-admits (1, -1); the filter drops it

            withClue("seed=$seed: the forced gate holds nothing and (1,-1) is re-admitted") {
                gated.bufferedWaves shouldBe 0
                q.current() shouldBe selfJoinMinusE(setOf(row(1, 2), row(2, -1)), filter = { y -> y > 0 })
                q.current() shouldBe setOf(row(1, -1))
            }
        }
    }

    /**
     * The F-15 pin, reproducing shape: `q(X, Z) :- e(X, Y), Y > 0, f(Y, Z), not e(X, Z).` The
     * arms share `e`; the left arm is `src:e → Filter → Join(·, src:f) → antijoin`, and this time
     * the join's other inlet is fed by `f`, so a wave on `e` that the filter drops has NO other
     * path to the antijoin's left inlet. The depth rule correctly leaves it ungated
     * ([LoweringDiagnostic.GateNotProvable]). Forcing the gate on in test scope and ending on an
     * `e`-removal the filter drops but the witness carries — the removal that should RE-ADMIT a
     * blocked answer — leaves that wave buffered at rest and the answer MISSING from `q`: the
     * withheld-at-rest signature of `FrontierGatedEmissionTest`'s two-hop case, through a
     * `FilterCell`→`JoinSetCell` arm. This is the reason cab.4-D6's depth rule exists.
     */
    @Test
    fun `F-15 reproduces on a compiled shape - forcing the gate on a filter-then-join arm withholds a re-admitted answer at rest`() {
        val catalog = PlanFixtures.catalog("e" to 2, "f" to 2)
        val compiled = compile("q(X, Z) :- e(X, Y), Y > 0, f(Y, Z), not e(X, Z).", catalog)
        val (antijoinHandle, factory) = antijoinOf(compiled)
        factory.emitOnFrontier shouldBe false
        compiled.diagnostics.single { it.handle == antijoinHandle }
            .shouldBeInstanceOf<LoweringDiagnostic.GateNotProvable>()

        // Batch fold over e = {(5,1)}, f = {(1,-1)}: (5,-1) is a live, unblocked answer.
        val batch = setOf(row(5, -1))
        forEachSeed(SEEDS) { seed ->
            for (forceGate in listOf(false, true)) {
                val world = SimWorld(seed = seed)
                val (applied, cell) = withCapturedAntijoin(compiled, antijoinHandle, world, forceGate = forceGate)
                val e = writerOf(world, applied, "e")
                val f = writerOf(world, applied, "f")
                val q = viewOf(world, applied, "q")

                e.add(row(5, 1)); world.runToIdle() // Y = 1 passes the filter
                f.add(row(1, -1)); world.runToIdle() // joins: candidate answer (5, -1)
                e.add(row(5, -1)); world.runToIdle() // witness blocks (5, -1); the filter drops it
                e.remove(row(5, -1)); world.runToIdle() // LAST wave: re-admits (5, -1); the filter drops it

                if (!forceGate) {
                    withClue("seed=$seed control: the shipped ungated lowering re-admits (5,-1)") {
                        cell.bufferedWaves shouldBe 0
                        q.current() shouldBe batch
                    }
                } else {
                    withClue("seed=$seed forced gate: the batch fold holds (5,-1), the gate withholds it at rest") {
                        cell.bufferedWaves shouldBeGreaterThanOrEqual 1
                        q.current() shouldNotBe batch
                        batch.filterNot { it in q.current() } shouldHaveSize 1
                    }
                }
            }
        }
    }

    // --------------------------------------------------------------------------- helpers

    private class OpTally(var adds: Int = 0, var removes: Int = 0, var idlePoints: Int = 0, var nonEmpty: Int = 0) {
        /** Records one idle-point comparison against [expected], so the sweep can prove it was not vacuous. */
        fun idle(expected: Set<Row>): Set<Row> {
            idlePoints++
            if (expected.isNotEmpty()) nonEmpty++
            return expected
        }

        /**
         * The task's floor — at least 30% of the driven operations are removals — and a
         * non-vacuity floor: at least a quarter of the idle points compared a non-empty answer.
         */
        fun assertRemovalRatio() {
            withClue("adds=$adds removes=$removes idlePoints=$idlePoints nonEmpty=$nonEmpty") {
                (removes * 10) shouldBeGreaterThanOrEqual (adds + removes) * 3
                (nonEmpty * 4) shouldBeGreaterThanOrEqual idlePoints
            }
        }
    }

    /**
     * One seeded step: one to three operations on a binary relation over [domain]. A removal
     * (40% when anything is live) always targets a row live at the step's start, and no row is
     * both added and removed in one step, so the relation's contents after the step are
     * independent of how the scheduler interleaves the step's invocations.
     */
    private class Script(private val random: Random, private val domain: IntRange) {
        fun step(live: MutableSet<Row>, writer: SetOps<Row>, tally: OpTally) {
            val atStart = live.toList()
            val touched = mutableSetOf<Row>()
            repeat(1 + random.nextInt(3)) {
                if (atStart.isNotEmpty() && random.nextInt(10) < 4) {
                    val victim = atStart[random.nextInt(atStart.size)]
                    if (victim in touched || victim !in live) return@repeat
                    touched += victim
                    live -= victim
                    writer.remove(victim)
                    tally.removes++
                } else {
                    val edge = Row(listOf(pick(), pick()))
                    if (edge in touched || edge in live) return@repeat
                    touched += edge
                    live += edge
                    writer.add(edge)
                    tally.adds++
                }
            }
        }

        private fun pick(): Int = domain.first + random.nextInt(domain.last - domain.first + 1)
    }

    private fun compile(source: String, catalog: Catalog): CompiledQuery {
        val query = (QueryParser.parse(source, catalog) as ParseResult.Parsed).query
        val plan = Planner.plan(query)
        val lowered = Lowering.lower(plan, catalog).shouldBeInstanceOf<LoweringResult.Lowered>()
        return CompiledQuery.from(lowered, plan)
    }

    /** The compiled query's one negated [SemiJoinFactory] spawn, by factory type. */
    private fun antijoinOf(compiled: CompiledQuery): Pair<String, SemiJoinFactory> {
        val antijoins = compiled.spec.lowered()
            .filterIsInstance<SpawnStep>()
            .filter { (it.factory as? SemiJoinFactory)?.negated == true }
        antijoins shouldHaveSize 1
        return antijoins.single().handle to antijoins.single().factory as SemiJoinFactory
    }

    /**
     * Applies [compiled] to [world] with the antijoin at [handle] wrapped in a [CapturingFactory]
     * (and, when [forceGate], its gate forced on); returns the applied query and the live cell.
     */
    private fun withCapturedAntijoin(
        compiled: CompiledQuery,
        handle: String,
        world: SimWorld,
        forceGate: Boolean = false,
    ): Pair<AppliedQuery, SemiJoinCell<Row, Row, Row>> {
        val sink = ArrayList<SemiJoinCell<Row, Row, Row>>()
        val steps = compiled.spec.lowered().map { step ->
            if (step is SpawnStep && step.handle == handle) {
                val lowered = step.factory as SemiJoinFactory
                val delegate = if (forceGate) lowered.copy(emitOnFrontier = true) else lowered
                step.copy(factory = CapturingFactory(delegate, sink))
            } else {
                step
            }
        }
        val applied = compiled.copy(spec = GraphSpec(steps)).applyTo(world.host.managementInlet)
        world.runToIdle()
        sink shouldHaveSize 1
        return applied to sink.single()
    }

    /** Delegates to the lowered [delegate] verbatim and remembers the cell it built. */
    private data class CapturingFactory(
        val delegate: SemiJoinFactory,
        val sink: ArrayList<SemiJoinCell<Row, Row, Row>>,
    ) : TypedCellFactory<SemiJoinCell<Row, Row, Row>> {
        override fun create(ref: CellRef): SemiJoinCell<Row, Row, Row> = delegate.create(ref).also { sink += it }
    }

    private fun writerOf(world: SimWorld, applied: AppliedQuery, relation: String): SetOps<Row> =
        world.host.lookup(applied.sources.getValue(relation))!!.inlet.call

    private fun viewOf(world: SimWorld, applied: AppliedQuery, root: String): SetView<Row> {
        val view = SetView<Row>()
        world.host.lookup(TypedRef<SetApi<Row>>(applied.outputs.getValue(root).ref))!!
            .outlet.subscribe(Use.fixed(Propagate { delta -> view.apply(delta) }, PortRef.generate()))
        return view
    }

    private fun row(vararg values: Int): Row = Row(values.toList())

    private companion object {
        /** Fixed seed range; every seed runs ([forEachSeed]). */
        val SEEDS = 0L until 50L

        /** Steps per seed; each step is one to three operations followed by `runToIdle`. */
        const val STEPS = 40

        /** The depth case: `Y > 0` is pushed onto `e(X, Y)`'s scan, under the join. */
        const val DEEP_ARM_QUERY = "q(X, Z) :- e(X, Y), e(Y, Z), Y > 0, not e(X, Z)."

        /** Batch fold `{(x,z) | (x,y),(y,z) ∈ E, filter(y), (x,z) ∉ E}`. */
        fun selfJoinMinusE(edges: Set<Row>, filter: (Int) -> Boolean): Set<Row> =
            selfJoinMinus(edges, edges, filter)

        /** Batch fold `{(x,z) | (x,y),(y,z) ∈ E, filter(y), (x,z) ∉ blocked}`. */
        fun selfJoinMinus(edges: Set<Row>, blocked: Set<Row>, filter: (Int) -> Boolean): Set<Row> =
            edges.flatMap { first ->
                val y = first.values[1] as Int
                if (!filter(y)) emptyList()
                else edges.filter { it.values[0] == y }.map { Row(listOf(first.values[0], it.values[1])) }
            }.filterNot { it in blocked }.toSet()
    }
}
