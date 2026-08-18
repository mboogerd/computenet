package civictech.oracle.run

import civictech.cell.data.SetCell
import civictech.cell.data.op.FilterCell
import civictech.oracle.model.Membership
import civictech.oracle.model.ModelState
import civictech.oracle.model.Script
import civictech.oracle.model.ScriptEvent
import civictech.oracle.model.SourceId
import civictech.oracle.model.SourceScript
import civictech.oracle.model.WriterId
import civictech.testkit.SimWorld
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * Ex/BYO — the `[ORA1-DIFF-11]` demonstration: a caller hand-builds its graph, hand-writes its
 * script, supplies a plain Kotlin fold as its reference, and gets a [RunOutcome] back.
 * Nothing generated is involved, and nothing BYO-only is involved either: the comparison,
 * report and taxonomy exercised here are the ones the generated path reuses.
 *
 * The graph is `SetCell -> FilterCell -> SetTerminalFold`, the smallest shape with a real
 * derived operator in it — a source-only case would compare the kernel's OR-set to the
 * model's membership rule and never touch an operator at all.
 */
class DifferentialRunnerCheckTest {

    private val source = SourceId("s")
    private val writer = WriterId("w")

    /** `<= "e"` — the predicate both the kernel graph and every reference below apply. */
    private val passes: (String) -> Boolean = { it <= "e" }

    /**
     * `add(a)`, `add(f)`, `add(c)`, then an observed `remove(a)`.
     *
     * Membership: the remove was issued by the writer that issued `add(a)`, so it covers it
     * and `a` is dead; `f` and `c` stay live. Through the filter, the terminal is `{c}` —
     * `f` is live but filtered out, which is what makes this case check the operator rather
     * than only the source.
     */
    private val script = Script(
        listOf(
            SourceScript(
                source,
                listOf(
                    ScriptEvent.Add(writer, "a"),
                    ScriptEvent.Add(writer, "f"),
                    ScriptEvent.Add(writer, "c"),
                    ScriptEvent.Remove(writer, "a"),
                ),
            ),
        ),
    )

    /** Spawns the case's cells on [world] and returns its terminal and its script source. */
    private fun buildGraph(world: SimWorld): CaseGraph {
        val setCell = SetCell<String>()
        val filter = FilterCell<String>(predicate = passes)
        val fold = SetTerminalFold<String>()

        world.host.managementInlet.call.spawn(setCell)
        world.host.managementInlet.call.spawn(filter)
        world.host.managementInlet.call.spawn(fold)
        world.host.managementInlet.call.connect(setCell.ref, "outlet", filter.ref, "inlet")
        world.host.managementInlet.call.connect(filter.ref, "outlet", fold.ref, "inlet")

        return CaseGraph(
            terminals = mapOf("filtered" to fold),
            sources = mapOf(source to setCell.inlet.call.asScriptSource()),
        )
    }

    /**
     * The honest reference: observed-remove membership over the slice, then the same
     * predicate — a plain Kotlin fold, executing no kernel cell.
     */
    private val honestReference = Reference { s ->
        mapOf(
            "filtered" to ModelState.SetState(
                Membership.live(s.slice(source)).filter { passes(it as String) }.toSet(),
            ),
        )
    }

    @Test
    fun `Ex-BYO a caller-supplied graph, script and reference reach Success`() {
        val outcome = DifferentialRunner.check(
            seed = 7L,
            caseMarker = "BYO SetCell -> FilterCell(<= e) -> terminal",
            script = script,
            reference = honestReference,
            buildGraph = ::buildGraph,
        )

        outcome shouldBe RunOutcome.Success
    }

    @Test
    fun `a substituted wrong reference yields Mismatch with every report field populated`() {
        // The controls feature substitutes a deliberately divergent reference; this is that
        // substitution, and it needs no change to check(...)'s signature — which is the
        // property the acceptance criterion names. This reference ignores removes, so it
        // expects the covered `a` to still be live.
        val ignoresRemoves = Reference { s ->
            mapOf(
                "filtered" to ModelState.SetState(
                    s.slice(source).events
                        .filterIsInstance<ScriptEvent.Add>()
                        .map { it.element }
                        .filter { passes(it as String) }
                        .toSet(),
                ),
            )
        }

        val outcome = DifferentialRunner.check(
            seed = 11L,
            caseMarker = "BYO SetCell -> FilterCell(<= e) -> terminal",
            script = script,
            reference = ignoresRemoves,
            buildGraph = ::buildGraph,
        )

        // [ORA1-DIFF-02]'s field list, asserted field by field rather than on a rendered
        // message: every one of them has to be readable off the value.
        val mismatch = outcome.shouldBeInstanceOf<RunOutcome.Mismatch>()
        mismatch.seed shouldBe 11L
        mismatch.terminal shouldBe "filtered"
        mismatch.renderedGraphSpec shouldBe "BYO SetCell -> FilterCell(<= e) -> terminal"
        mismatch.script shouldBe script
        mismatch.expected shouldBe ModelState.SetState(setOf("a", "c"))
        mismatch.actual shouldBe ModelState.SetState(setOf("c"))

        val difference = mismatch.difference.shouldBeInstanceOf<StateDifference.SetDifference>()
        difference.onlyInExpected shouldBe setOf("a")
        difference.onlyInActual shouldBe emptySet()
    }

    @Test
    fun `an Observe event injects nothing into the kernel and does not change the outcome`() {
        // Observe is a model-only causality statement (Membership's rule): there is no kernel
        // call that means "this writer has now seen everything". A runner that tried to inject
        // it would have to invent one; a runner that treated it as an unknown event would
        // fail the case. Neither happens — the same case with Observe events interleaved is
        // still Success, and the model's answer is unchanged because the single writer already
        // observes its own adds.
        val observing = Script(
            listOf(
                SourceScript(
                    source,
                    listOf(
                        ScriptEvent.Add(writer, "a"),
                        ScriptEvent.Observe(writer),
                        ScriptEvent.Add(writer, "f"),
                        ScriptEvent.Add(writer, "c"),
                        ScriptEvent.Observe(writer),
                        ScriptEvent.Remove(writer, "a"),
                    ),
                ),
            ),
        )

        val outcome = DifferentialRunner.check(
            seed = 3L,
            caseMarker = "BYO with Observe events",
            script = observing,
            reference = honestReference,
            buildGraph = ::buildGraph,
        )

        outcome shouldBe RunOutcome.Success
    }
}
