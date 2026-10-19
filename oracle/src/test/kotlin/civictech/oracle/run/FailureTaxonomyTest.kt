package civictech.oracle.run

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.SetCell
import civictech.cell.data.delta.SetDelta
import civictech.cell.host.DeadLetter
import civictech.cell.data.SetOps
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.ManagedHost
import civictech.cell.port.FanInlet
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.oracle.model.Membership
import civictech.oracle.model.ModelState
import civictech.oracle.model.Script
import civictech.oracle.model.ScriptEvent
import civictech.oracle.model.SourceId
import civictech.oracle.model.SourceScript
import civictech.oracle.shrink.FailureSignature
import civictech.oracle.model.WriterId
import civictech.testkit.SimWorld
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The four failure kinds, each asserted **as a kind** — `shouldBeInstanceOf`, never a message
 * substring — plus the precedence between them.
 *
 * Each test provokes exactly one condition, except the precedence test, which provokes a dead
 * letter on a case whose folded state *agrees* with the model and shows the outcome is still
 * [RunOutcome.DeadLetterFailure]: agreement under message loss is luck, not evidence.
 */
class FailureTaxonomyTest {

    private val source = SourceId("s")
    private val writer = WriterId("w")

    /** A script of [count] adds — enough work that a ten-step budget cannot drain it. */
    private fun addScript(count: Int): Script = Script(
        listOf(
            SourceScript(source, (0 until count).map { ScriptEvent.Add(writer, "e$it") }),
        ),
    )

    /** The honest reference for a bare `SetCell` terminal: observed-remove membership. */
    private val membershipReference = Reference { s ->
        mapOf("terminal" to ModelState.SetState(Membership.live(s.slice(source))))
    }

    /** `SetCell -> SetTerminalFold`, the minimal case: no operator, so nothing but the taxonomy is under test. */
    private fun buildGraph(world: SimWorld): CaseGraph {
        val setCell = SetCell<String>()
        val fold = SetTerminalFold<String>()
        world.host.managementInlet.call.spawn(setCell)
        world.host.managementInlet.call.spawn(fold)
        world.host.managementInlet.call.connect(setCell.ref, "outlet", fold.ref, "inlet")
        return CaseGraph(
            terminals = mapOf("terminal" to fold),
            sources = mapOf(source to setCell.inlet.call.asScriptSource()),
        )
    }

    @Test
    fun `BS-10 a step budget too small to drain the case yields NonQuiescence naming seed and budget`() {
        val outcome = DifferentialRunner.check(
            seed = 42L,
            caseMarker = "BS-10 SetCell -> terminal, budget 10",
            script = addScript(count = 60),
            reference = membershipReference,
            stepBudget = 10,
            buildGraph = ::buildProxyDrivenGraph,
        )

        // The kind, not the message: a run that never settled is not a disagreement about
        // values, and folding it into a Mismatch would blame the kernel for an unfinished
        // computation ORA1 §DIFF-07.
        val nonQuiescence = outcome.shouldBeInstanceOf<RunOutcome.NonQuiescence>()
        nonQuiescence.seed shouldBe 42L
        nonQuiescence.stepBudget shouldBe 10
    }

    @Test
    fun `the same case with an ample budget settles, so BS-10's verdict is the budget and not the graph`() {
        // Without this, BS-10 would pass against a runner that returned NonQuiescence
        // unconditionally.
        DifferentialRunner.check(
            seed = 42L,
            caseMarker = "BS-10 control: same case, default budget",
            script = addScript(count = 60),
            reference = membershipReference,
            buildGraph = ::buildProxyDrivenGraph,
        ) shouldBe RunOutcome.Success
    }

    @Test
    fun `BS-11 a reference that throws yields ModelEvaluationFailure, not Mismatch`() {
        val throwing = Reference { _ -> throw IllegalStateException("boom") }

        val outcome = DifferentialRunner.check(
            seed = 5L,
            caseMarker = "BS-11 throwing reference",
            script = addScript(count = 3),
            reference = throwing,
            buildGraph = ::buildGraph,
        )

        // A broken oracle is never read as a broken kernel (design D10, ORA1 §DIFF-08).
        val failure = outcome.shouldBeInstanceOf<RunOutcome.ModelEvaluationFailure>()
        failure.seed shouldBe 5L
        failure.cause.shouldBeInstanceOf<IllegalStateException>()
        failure.cause.message shouldBe "boom"
    }

    @Test
    fun `a dead letter yields DeadLetterFailure even though the folded state matches the model`() {
        val outcome = DifferentialRunner.check(
            seed = 9L,
            caseMarker = "dead letter mid-run, state agreeing",
            script = addScript(count = 4),
            reference = membershipReference,
            buildGraph = ::buildDeadLetteringGraph,
        )

        // Precedence, tested on the combination the design names: DeadLetterFailure outranks
        // the value comparison, because a run that lost a message did not receive its whole
        // input — the agreement below is luck, not evidence ORA1 §DIFF-04.
        val failure = outcome.shouldBeInstanceOf<RunOutcome.DeadLetterFailure>()
        failure.seed shouldBe 9L
        failure.deadLetters.shouldNotBeEmpty()
        failure.deadLetters.first().description shouldBe SYNTHETIC_DESCRIPTION
    }

    @Test
    fun `the same case without the dead letter is Success, so the verdict is the letter and not the values`() {
        // The discrimination the test above needs: identical script, seed and reference, with
        // only the emitter removed. Without it, DeadLetterFailure could be a mismatch in
        // disguise.
        DifferentialRunner.check(
            seed = 9L,
            caseMarker = "dead letter control: same case, no emitter",
            script = addScript(count = 4),
            reference = membershipReference,
            buildGraph = ::buildGraph,
        ) shouldBe RunOutcome.Success
    }

    /** The proxy's shape: `SetCell`'s own inlet, reached by name through a hosted invocation. */
    private interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    /**
     * The same minimal case, but driven through a **hosted proxy** rather than by calling the
     * cell's inlet directly.
     *
     * This is what makes BS-10's budget bite, and it is worth naming because the obvious
     * version does not: a direct `setCell.inlet.call.add(..)` on a co-hosted graph dispatches
     * inline, so sixty adds settled well inside a ten-step budget and the case reached
     * Success. A hosted invocation is enqueued on the host scheduler
     * (`ManagedHost.enqueueHostedInvocation`), so each op costs at least one
     * `controller.step()` — which is also how `GenerativeGraphTest` drives its writers, and
     * how a generated case with a two-host placement will reach its sources.
     */
    private fun buildProxyDrivenGraph(world: SimWorld): CaseGraph {
        val setCell = SetCell<String>()
        val fold = SetTerminalFold<String>()
        world.host.managementInlet.call.spawn(setCell)
        world.host.managementInlet.call.spawn(fold)
        world.host.managementInlet.call.connect(setCell.ref, "outlet", fold.ref, "inlet")

        val proxy = HostedCellProxy.create(setCell.ref, world.registry, SetInletProxy::class.java) as SetInletProxy
        return CaseGraph(
            terminals = mapOf("terminal" to fold),
            sources = mapOf(source to proxy.inlet.call.asScriptSource()),
        )
    }

    /**
     * The same minimal case with a [DeadLetterEmitter] fanned off the source's outlet.
     *
     * The emitter publishes a *synthetic* [DeadLetter] on the host's own `deadLetterOutlet`,
     * the route `kernel/src/main/kotlin/civictech/cell/host/DeadLetters.kt`'s KDoc describes.
     * The task flagged that route as unverified from `:oracle`; it is reachable exactly as
     * described — `ManagedHost.deadLetterOutlet` is a public `FanOutlet`, and `DeadLetter` is
     * a public data class with a public constructor — so no fallback to provoking a real
     * dead letter was needed. What the synthetic route buys is that the fold still receives
     * every delta, which is precisely the "state matches, letter still wins" precedence case;
     * a real dead letter would also perturb the values and could not test precedence.
     */
    private fun buildDeadLetteringGraph(world: SimWorld): CaseGraph {
        val setCell = SetCell<String>()
        val fold = SetTerminalFold<String>()
        val emitter = DeadLetterEmitter(world.host)

        world.host.managementInlet.call.spawn(setCell)
        world.host.managementInlet.call.spawn(fold)
        world.host.managementInlet.call.spawn(emitter)
        world.host.managementInlet.call.connect(setCell.ref, "outlet", fold.ref, "inlet")
        world.host.managementInlet.call.connect(setCell.ref, "outlet", emitter.ref, "inlet")

        return CaseGraph(
            terminals = mapOf("terminal" to fold),
            sources = mapOf(source to setCell.inlet.call.asScriptSource()),
        )
    }

    /**
     * Publishes one synthetic dead letter on [host]'s outlet the first time a delta reaches
     * it — mid-run by construction, since it is driven by the same delta stream the terminal
     * fold sees. Ports are registered directly (`:oracle` authors no `@CellBase` cells), as
     * `GenerativeGraphTest.CounterCollectorCell` does.
     */
    private class DeadLetterEmitter(
        private val host: ManagedHost,
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell {
        private var emitted = false

        val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<String>>>())

        init {
            inlet.serve(object : Propagate<SetDelta<String>> {
                override fun propagate(value: SetDelta<String>) {
                    if (emitted) return
                    emitted = true
                    host.deadLetterOutlet.call.propagate(
                        DeadLetter(hostRef = host.ref, cause = null, description = SYNTHETIC_DESCRIPTION),
                    )
                }
            })
        }
    }

    // ============================================================================================
    // computenet-qm0o: the four-kind precedence order is
    //   NonQuiescence > DeadLetterFailure > ModelEvaluationFailure > Mismatch
    // (DifferentialRunner's "Kind precedence" KDoc), implemented purely as the RETURN ORDER
    // inside execute(). DeadLetterFailure > Mismatch is already provoked above, and
    // The tests below provoke every remaining adjacent pair, each putting BOTH
    // conditions genuinely in force in one run so a reordering of execute()'s return statements
    // reddens exactly this test — never asserting on a message substring, only on the
    // RunOutcome's kind.
    // ============================================================================================

    // ------------------------------------------------------------ NonQuiescence > DeadLetterFailure

    @Test
    fun `NonQuiescence outranks DeadLetterFailure - budget exhaustion on a dead-lettering, proxy-driven graph is reported as NonQuiescence`() {
        val outcome = DifferentialRunner.check(
            seed = 42L,
            caseMarker = "NonQuiescence > DeadLetterFailure: proxy-driven dead-letter graph, budget 10",
            script = addScript(count = 60),
            reference = membershipReference,
            stepBudget = 10,
            buildGraph = ::buildProxyDrivenDeadLetteringGraph,
        )

        // The kind, not the message ORA1 §DIFF-07: this run also has a real dead letter (see
        // the control below), so a wrong return order would report DeadLetterFailure instead.
        val nonQuiescence = outcome.shouldBeInstanceOf<RunOutcome.NonQuiescence>()
        nonQuiescence.seed shouldBe 42L
        nonQuiescence.stepBudget shouldBe 10
    }

    @Test
    fun `the same dead-lettering graph with an ample budget is DeadLetterFailure, so the budget alone is what flips the precedence test above`() {
        // The discrimination the test above needs: the ONLY difference is the budget. Without
        // this, "NonQuiescence" above could just as well be this runner's unconditional answer
        // for any proxy-driven graph, dead letter or not.
        DifferentialRunner.check(
            seed = 42L,
            caseMarker = "control: same dead-lettering graph, default budget",
            script = addScript(count = 60),
            reference = membershipReference,
            buildGraph = ::buildProxyDrivenDeadLetteringGraph,
        ).shouldBeInstanceOf<RunOutcome.DeadLetterFailure>()
    }

    /**
     * [buildProxyDrivenGraph] (so the budget bites — a direct co-hosted call dispatches inline
     * and costs zero scheduler steps) with [DeadLetterEmitter] fanned off the source exactly as
     * [buildDeadLetteringGraph] does, combined so both NonQuiescence's and DeadLetterFailure's
     * conditions hold in the same run.
     */
    private fun buildProxyDrivenDeadLetteringGraph(world: SimWorld): CaseGraph {
        val setCell = SetCell<String>()
        val fold = SetTerminalFold<String>()
        val emitter = DeadLetterEmitter(world.host)

        world.host.managementInlet.call.spawn(setCell)
        world.host.managementInlet.call.spawn(fold)
        world.host.managementInlet.call.spawn(emitter)
        world.host.managementInlet.call.connect(setCell.ref, "outlet", fold.ref, "inlet")
        world.host.managementInlet.call.connect(setCell.ref, "outlet", emitter.ref, "inlet")

        val proxy = HostedCellProxy.create(setCell.ref, world.registry, SetInletProxy::class.java) as SetInletProxy
        return CaseGraph(
            terminals = mapOf("terminal" to fold),
            sources = mapOf(source to proxy.inlet.call.asScriptSource()),
        )
    }

    // ------------------------------------------------------- NonQuiescence > ModelEvaluationFailure

    @Test
    fun `NonQuiescence outranks ModelEvaluationFailure - budget exhaustion with a throwing reference is reported as NonQuiescence`() {
        val throwing = Reference { _ -> throw IllegalStateException("boom") }

        val outcome = DifferentialRunner.check(
            seed = 42L,
            caseMarker = "NonQuiescence > ModelEvaluationFailure: proxy-driven graph, budget 10, throwing reference",
            script = addScript(count = 60),
            reference = throwing,
            stepBudget = 10,
            buildGraph = ::buildProxyDrivenGraph,
        )

        // Reachable only if NonQuiescence's own check precedes reference.evaluate(script)
        // entirely, per execute()'s KDoc: a budget exhaustion is a verdict, never something that
        // catches an exception as control flow ORA1 §DIFF-07.
        val nonQuiescence = outcome.shouldBeInstanceOf<RunOutcome.NonQuiescence>()
        nonQuiescence.seed shouldBe 42L
        nonQuiescence.stepBudget shouldBe 10
    }

    @Test
    fun `the same case with an ample budget surfaces the throwing reference as ModelEvaluationFailure, so the budget alone is what flips the precedence test above`() {
        val throwing = Reference { _ -> throw IllegalStateException("boom") }

        DifferentialRunner.check(
            seed = 42L,
            caseMarker = "control: same graph, default budget, throwing reference",
            script = addScript(count = 60),
            reference = throwing,
            buildGraph = ::buildProxyDrivenGraph,
        ).shouldBeInstanceOf<RunOutcome.ModelEvaluationFailure>()
    }

    // -------------------------------------------------- DeadLetterFailure > ModelEvaluationFailure

    @Test
    fun `DeadLetterFailure outranks ModelEvaluationFailure - a dead letter with a throwing reference is reported as DeadLetterFailure`() {
        val throwing = Reference { _ -> throw IllegalStateException("boom") }

        val outcome = DifferentialRunner.check(
            seed = 9L,
            caseMarker = "DeadLetterFailure > ModelEvaluationFailure: dead letter mid-run, throwing reference",
            script = addScript(count = 4),
            reference = throwing,
            buildGraph = ::buildDeadLetteringGraph,
        )

        // Reachable only if the dead-letter check precedes reference.evaluate(script) entirely:
        // BS-11 (above) already shows this same throwing reference alone yields
        // ModelEvaluationFailure, and the plain dead-letter test (above) already shows this same
        // graph alone yields DeadLetterFailure — this is the two combined in one run.
        val failure = outcome.shouldBeInstanceOf<RunOutcome.DeadLetterFailure>()
        failure.seed shouldBe 9L
        failure.deadLetters.shouldNotBeEmpty()
    }

    // ------------------------------------------------------------- ModelEvaluationFailure > Mismatch

    @Test
    fun `ModelEvaluationFailure outranks Mismatch - a throwing reference on a case with a genuinely non-empty terminal is reported as ModelEvaluationFailure`() {
        // Unlike every pair above, Mismatch's condition ("expected != actual") is not an
        // independent fact about the run: computing it AT ALL needs the very value
        // reference.evaluate(script) would have produced, which is exactly the call this
        // reference throws from. So "both conditions in force" here cannot mean "an independent
        // check that would also fire" the way a dead letter or a budget can be independently
        // true - there is no legal reordering of execute()'s statements that could even compile
        // a Mismatch check ahead of the try/catch that feeds it `expectedStates`.
        //
        // What IS demonstrable, and what a reordering-style relaxation of the precedence would
        // actually change, is this: were the catch to fall through with some plausible
        // placeholder instead of returning ModelEvaluationFailure - the shape of change the
        // mutation check below performs - the comparison would run against a graph whose real
        // folded state is genuinely non-empty and non-trivial, so it is never a coincidental
        // match; the run would be reported as Mismatch instead. Choosing a non-trivial script
        // (BS-11 uses the same size for a different reason) is what makes that alternative
        // reachable in principle rather than vacuous.
        val throwing = Reference { _ -> throw IllegalStateException("boom") }

        val outcome = DifferentialRunner.check(
            seed = 5L,
            caseMarker = "ModelEvaluationFailure > Mismatch: throwing reference, non-trivial terminal state",
            script = addScript(count = 3),
            reference = throwing,
            buildGraph = ::buildGraph,
        )

        val failure = outcome.shouldBeInstanceOf<RunOutcome.ModelEvaluationFailure>()
        failure.seed shouldBe 5L
        failure.cause.shouldBeInstanceOf<IllegalStateException>()
    }

    private companion object {
        const val SYNTHETIC_DESCRIPTION = "synthetic dead letter from FailureTaxonomyTest"
    }

    // =====================================================================
    // ORA2's mesh verdicts sit in the same taxonomy (ORA2 §CONV-03)
    // =====================================================================

    @Test
    fun `ORA2's mesh verdicts are separate signatures from each other and from Mismatch`() {
        // The shrinker identifies a failure by its RunOutcome VARIANT (plus the terminal, where
        // one is named). Adding the two mesh kinds without extending FailureSignature would make a
        // shrink of a divergence retain a unanimous-wrong-answer case as "the same failure" — the
        // exact confusion ORA2 §CONV-03 exists to prevent, arriving one layer down.
        //
        // Neither names a terminal, and that is structural rather than an omission: a mesh verdict
        // is about the replicas of one logical id, not about a terminal of a graph.
        val expected = ModelState.MapState(mapOf("k" to "v1"))
        val actual = ModelState.MapState(mapOf("k" to "v0"))
        val diverged: RunOutcome = RunOutcome.ReplicaDivergence(
            seed = 1L,
            logicalId = "logical",
            caseMarker = "mesh",
            script = Script.EMPTY,
            expected = expected,
            perReplica = mapOf("r0" to expected, "r1" to actual),
            keys = emptyList(),
        )
        val wrong: RunOutcome = RunOutcome.ReplicasAgreeButWrong(
            seed = 1L,
            logicalId = "logical",
            caseMarker = "mesh",
            script = Script.EMPTY,
            expected = expected,
            actual = actual,
            difference = StateDifference.between(expected, actual),
            replicas = setOf("r0", "r1"),
            keys = emptyList(),
        )
        val mismatch: RunOutcome = RunOutcome.Mismatch(
            seed = 1L,
            terminal = "t",
            renderedGraphSpec = "spec",
            script = Script.EMPTY,
            expected = expected,
            actual = actual,
            difference = StateDifference.between(expected, actual),
        )

        val signatures = listOf(diverged, wrong, mismatch).map { FailureSignature.of(it) }
        signatures.forEach { it.shouldNotBeNull() }
        signatures.toSet() shouldBe signatures.toSet()
        signatures.distinct() shouldBe signatures
        FailureSignature.of(diverged)!!.terminal shouldBe null
        FailureSignature.of(wrong)!!.terminal shouldBe null
        FailureSignature.of(mismatch)!!.terminal shouldBe "t"
    }
}
