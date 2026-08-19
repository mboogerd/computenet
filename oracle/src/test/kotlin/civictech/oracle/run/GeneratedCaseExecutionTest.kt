package civictech.oracle.run

import civictech.cell.graph.ConnectStep
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.GraphStep
import civictech.cell.graph.SpawnStep
import civictech.oracle.bind.CoreOperators
import civictech.oracle.bind.OperatorCatalog
import civictech.oracle.gen.CaseScript
import civictech.oracle.gen.CaseStep
import civictech.oracle.gen.CaseTopology
import civictech.oracle.gen.GeneratedCase
import civictech.oracle.gen.TerminalSpec
import civictech.oracle.gen.TopologyNode
import civictech.oracle.model.ModelState
import civictech.oracle.model.ScriptEvent
import civictech.oracle.model.SourceId
import civictech.oracle.model.WriterId
import io.kotest.matchers.string.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * `[ORA1-DIFF-01]`'s **generated** path: [DifferentialRunner.run] over hand-CONSTRUCTED
 * [GeneratedCase] values.
 *
 * Hand-constructed on purpose, per the task's own sequencing note: invoking `CaseGenerator`
 * here would make this suite a test of the generator's current choices rather than of
 * execution, and a generator change would then break execution tests for no execution reason.
 * Every case below is therefore a topology + spec + script written out by hand, using the
 * catalog's own registered factories so the kernel half and the model half cannot drift.
 */
class GeneratedCaseExecutionTest {

    private val writer = WriterId("w")
    private val sourceA = SourceId("a")
    private val sourceB = SourceId("b")

    @BeforeEach
    fun registerCatalog() {
        CoreOperators.registerAll()
    }

    @AfterEach
    fun resetCatalog() {
        OperatorCatalog.reset()
    }

    /** The catalog's own kernel factory for [id] — the same one a generated case would lower. */
    private fun factory(id: String) = OperatorCatalog.entry(id)!!.kernel

    private fun spec(vararg steps: GraphStep) = GraphSpec(steps.toList())

    /**
     * `set(a) -> filter -> terminal` — the smallest generated shape with a real derived
     * operator in it. The registered `filter` predicate is `TEXT_LENGTH_IS_EVEN`
     * (`CoreOperators`), so even-length text survives and odd-length text does not.
     */
    private fun filterCase(script: CaseScript, seed: Long = 21L) = GeneratedCase(
        seed = seed,
        topology = CaseTopology(
            nodes = listOf(
                TopologyNode("src", CoreOperators.Ids.SET, emptyList(), sourceA),
                TopologyNode("flt", CoreOperators.Ids.FILTER, listOf("src"), null),
            ),
            terminals = listOf(TerminalSpec("filtered", "flt")),
            placement = mapOf("src" to 0, "flt" to 0),
        ),
        spec = spec(
            SpawnStep("src", factory(CoreOperators.Ids.SET)),
            SpawnStep("flt", factory(CoreOperators.Ids.FILTER)),
            ConnectStep("src", "outlet", "flt", "inlet"),
        ),
        script = script,
        removeAudit = emptyList(),
    )

    /** `add(aa) add(bbb) add(cc) remove(bbb)` — through the filter, `{aa, cc}`. */
    private fun filterScript() = CaseScript(
        listOf(
            CaseStep.Op(sourceA, ScriptEvent.Add(writer, "aa")),
            CaseStep.Op(sourceA, ScriptEvent.Add(writer, "bbb")),
            CaseStep.Op(sourceA, ScriptEvent.Add(writer, "cc")),
            CaseStep.Op(sourceA, ScriptEvent.Remove(writer, "bbb")),
        ),
    )

    @Test
    fun `a hand-built set-filter case runs to Success against the catalog-resolved model`() {
        DifferentialRunner.run(filterCase(filterScript())) shouldBe RunOutcome.Success
    }

    @Test
    fun `the case is not vacuous - its terminal really holds the filtered set`() {
        // Without this, Success above could be two empty states agreeing. The barrier hook is
        // the only read of a live fold this API exposes, so a trailing Barrier is how the test
        // asks what the kernel actually computed.
        var observed: Map<String, ModelState>? = null
        val withBarrier = CaseScript(filterScript().steps + CaseStep.Barrier)

        DifferentialRunner.run(filterCase(withBarrier)) { observed = it } shouldBe RunOutcome.Success

        observed shouldBe mapOf("filtered" to ModelState.SetState(setOf("aa", "cc")))
    }

    /** `set(a), set(b) -> union -> terminal` — the fan-in shape, both arms on the one `inlet`. */
    private fun unionCase(script: CaseScript) = GeneratedCase(
        seed = 33L,
        topology = CaseTopology(
            nodes = listOf(
                TopologyNode("s1", CoreOperators.Ids.SET, emptyList(), sourceA),
                TopologyNode("s2", CoreOperators.Ids.SET, emptyList(), sourceB),
                TopologyNode("u", CoreOperators.Ids.UNION, listOf("s1", "s2"), null),
            ),
            terminals = listOf(TerminalSpec("united", "u")),
            placement = mapOf("s1" to 0, "s2" to 0, "u" to 0),
        ),
        spec = spec(
            SpawnStep("s1", factory(CoreOperators.Ids.SET)),
            SpawnStep("s2", factory(CoreOperators.Ids.SET)),
            SpawnStep("u", factory(CoreOperators.Ids.UNION)),
            ConnectStep("s1", "outlet", "u", "inlet"),
            ConnectStep("s2", "outlet", "u", "inlet"),
        ),
        script = script,
        removeAudit = emptyList(),
    )

    @Test
    fun `a hand-built union fan-in case runs to Success`() {
        var observed: Map<String, ModelState>? = null
        val script = CaseScript(
            listOf(
                CaseStep.Op(sourceA, ScriptEvent.Add(writer, "x")),
                CaseStep.Op(sourceB, ScriptEvent.Add(writer, "y")),
                CaseStep.Op(sourceA, ScriptEvent.Add(writer, "z")),
                CaseStep.Op(sourceB, ScriptEvent.Remove(writer, "y")),
                CaseStep.Barrier,
            ),
        )

        DifferentialRunner.run(unionCase(script)) { observed = it } shouldBe RunOutcome.Success
        observed shouldBe mapOf("united" to ModelState.SetState(setOf("x", "z")))
    }

    /**
     * `set(a) -> count -> terminal`. `count` declares a `Scalar` output, so the runner picks a
     * [ScalarTerminalFold], which sums `CounterDelta.amount` as a **`Long`** — and `CountModel`
     * answers with a `Long` too. `ModelState.ScalarState` equality is structural, so
     * `ScalarState(2)` and `ScalarState(2L)` are different values: this case is the end-to-end
     * proof that the width survives catalog resolution.
     */
    private fun countCase() = GeneratedCase(
        seed = 55L,
        topology = CaseTopology(
            nodes = listOf(
                TopologyNode("src", CoreOperators.Ids.SET, emptyList(), sourceA),
                TopologyNode("cnt", CoreOperators.Ids.COUNT, listOf("src"), null),
            ),
            terminals = listOf(TerminalSpec("counted", "cnt")),
            placement = mapOf("src" to 0, "cnt" to 0),
        ),
        spec = spec(
            SpawnStep("src", factory(CoreOperators.Ids.SET)),
            SpawnStep("cnt", factory(CoreOperators.Ids.COUNT)),
            ConnectStep("src", "outlet", "cnt", "inlet"),
        ),
        script = CaseScript(
            listOf(
                CaseStep.Op(sourceA, ScriptEvent.Add(writer, "p")),
                CaseStep.Op(sourceA, ScriptEvent.Add(writer, "q")),
                CaseStep.Op(sourceA, ScriptEvent.Add(writer, "r")),
                CaseStep.Op(sourceA, ScriptEvent.Remove(writer, "q")),
                CaseStep.Barrier,
            ),
        ),
        removeAudit = emptyList(),
    )

    @Test
    fun `width - a count terminal reaches Success and folds a Long, not an Int`() {
        var observed: Map<String, ModelState>? = null

        DifferentialRunner.run(countCase()) { observed = it } shouldBe RunOutcome.Success

        // The assertion the width criterion is about: 2L, and NOT 2.
        observed shouldBe mapOf("counted" to ModelState.ScalarState(2L))
        observed shouldNotBe mapOf("counted" to ModelState.ScalarState(2))
    }

    /**
     * `set(a), set(b) -> presenceCount -> terminal`. `presenceCount` declares a `MapOf`
     * output, so the runner picks a [MapTerminalFold], whose values stay **`Int`** — the other
     * half of the width pinning, and the reason it is a different fold rather than a widened
     * scalar.
     */
    private fun presenceCountCase() = GeneratedCase(
        seed = 77L,
        topology = CaseTopology(
            nodes = listOf(
                TopologyNode("s1", CoreOperators.Ids.SET, emptyList(), sourceA),
                TopologyNode("s2", CoreOperators.Ids.SET, emptyList(), sourceB),
                TopologyNode("pc", CoreOperators.Ids.PRESENCE_COUNT, listOf("s1", "s2"), null),
            ),
            terminals = listOf(TerminalSpec("presence", "pc")),
            placement = mapOf("s1" to 0, "s2" to 0, "pc" to 0),
        ),
        spec = spec(
            SpawnStep("s1", factory(CoreOperators.Ids.SET)),
            SpawnStep("s2", factory(CoreOperators.Ids.SET)),
            SpawnStep("pc", factory(CoreOperators.Ids.PRESENCE_COUNT)),
            ConnectStep("s1", "outlet", "pc", "inlet"),
            ConnectStep("s2", "outlet", "pc", "inlet"),
        ),
        script = CaseScript(
            listOf(
                CaseStep.Op(sourceA, ScriptEvent.Add(writer, "shared")),
                CaseStep.Op(sourceB, ScriptEvent.Add(writer, "shared")),
                CaseStep.Op(sourceA, ScriptEvent.Add(writer, "onlyA")),
                CaseStep.Barrier,
            ),
        ),
        removeAudit = emptyList(),
    )

    @Test
    fun `width - a presenceCount terminal reaches Success and folds Int values, not Long`() {
        var observed: Map<String, ModelState>? = null

        DifferentialRunner.run(presenceCountCase()) { observed = it } shouldBe RunOutcome.Success

        observed shouldBe mapOf("presence" to ModelState.MapState(mapOf("shared" to 2, "onlyA" to 1)))
        observed shouldNotBe mapOf("presence" to ModelState.MapState(mapOf("shared" to 2L, "onlyA" to 1L)))
    }

    @Test
    fun `a topology naming an unregistered catalog id fails loudly, naming the id`() {
        val unregistered = GeneratedCase(
            seed = 1L,
            topology = CaseTopology(
                nodes = listOf(
                    TopologyNode("src", CoreOperators.Ids.SET, emptyList(), sourceA),
                    TopologyNode("mystery", "notRegisteredAnywhere", listOf("src"), null),
                ),
                terminals = listOf(TerminalSpec("t", "mystery")),
                placement = mapOf("src" to 0, "mystery" to 0),
            ),
            spec = spec(SpawnStep("src", factory(CoreOperators.Ids.SET))),
            script = CaseScript.EMPTY,
            removeAudit = emptyList(),
        )

        val failure = assertThrows<IllegalStateException> { DifferentialRunner.run(unregistered) }

        // Loud AND named — a silent skip is a green run that checked less than it claims.
        failure.message!! shouldContain "notRegisteredAnywhere"
    }

    @Test
    fun `a catalog id whose model is the wrong sub-interface fails loudly, naming the id`() {
        // `filter` is a unary OPERATOR, so its registered model is an OperatorModel. A topology
        // that uses it at arity 0 asks the runner to fold a script slice through it, which
        // OperatorModel cannot do — and getting a named refusal here is what stops that
        // becoming a mismatch blamed on the kernel.
        val misused = GeneratedCase(
            seed = 1L,
            topology = CaseTopology(
                nodes = listOf(TopologyNode("bad", CoreOperators.Ids.FILTER, emptyList(), sourceA)),
                terminals = listOf(TerminalSpec("t", "bad")),
                placement = mapOf("bad" to 0),
            ),
            spec = spec(SpawnStep("bad", factory(CoreOperators.Ids.FILTER))),
            script = CaseScript.EMPTY,
            removeAudit = emptyList(),
        )

        val failure = assertThrows<IllegalStateException> { DifferentialRunner.run(misused) }

        failure.message!! shouldContain CoreOperators.Ids.FILTER
    }

    @Test
    fun `a substituted wrong reference yields Mismatch carrying a non-empty rendered spec`() {
        // The controls feature's substitution, expressible with no signature change: this
        // reference ignores removes, so it expects the retracted `bbb`… which the filter drops
        // anyway, so it expects `aa`, `cc` AND nothing else — make it wrong on a surviving
        // element instead.
        val wrong = Reference { _ -> mapOf("filtered" to ModelState.SetState(setOf("aa"))) }

        val outcome = DifferentialRunner.run(filterCase(filterScript()), reference = wrong)

        val mismatch = outcome.shouldBeInstanceOf<RunOutcome.Mismatch>()
        mismatch.seed shouldBe 21L
        mismatch.terminal shouldBe "filtered"
        mismatch.expected shouldBe ModelState.SetState(setOf("aa"))
        mismatch.actual shouldBe ModelState.SetState(setOf("aa", "cc"))

        // [ORA1-DIFF-02]'s rendered-spec field, on the generated path: the real spec, with
        // handles and catalog ids visible — not an empty string.
        mismatch.renderedGraphSpec.shouldNotBeEmpty()
        mismatch.renderedGraphSpec shouldContain "spawn src : ${CoreOperators.Ids.SET}"
        mismatch.renderedGraphSpec shouldContain "spawn flt : ${CoreOperators.Ids.FILTER}"
        mismatch.renderedGraphSpec shouldContain "connect src.outlet -> flt.inlet"

        val difference = mismatch.difference.shouldBeInstanceOf<StateDifference.SetDifference>()
        difference.onlyInActual shouldBe setOf("cc")
    }

    @Test
    fun `a mid-script Barrier quiesces - the fold there equals the model of the script prefix`() {
        val prefix = listOf(
            CaseStep.Op(sourceA, ScriptEvent.Add(writer, "aa")),
            CaseStep.Op(sourceA, ScriptEvent.Add(writer, "bbb")),
        )
        val suffix = listOf(
            CaseStep.Op(sourceA, ScriptEvent.Add(writer, "cc")),
            CaseStep.Op(sourceA, ScriptEvent.Remove(writer, "aa")),
        )
        val case = filterCase(CaseScript(prefix + CaseStep.Barrier + suffix))

        // The model's answer for the PREFIX alone, computed through the same catalog
        // resolution — not a hand-written expectation, so the barrier is checked against the
        // oracle rather than against this test's opinion.
        val model = CaseExecution.referenceModelFor(case.topology)
        val expectedAtBarrier = model.eval(CaseScript(prefix).toScript())

        val readings = mutableListOf<Map<String, ModelState>>()
        DifferentialRunner.run(case) { readings += it } shouldBe RunOutcome.Success

        readings.size shouldBe 1
        readings.single() shouldBe expectedAtBarrier
        // And the barrier reading is genuinely mid-script: the final state differs from it.
        expectedAtBarrier shouldBe mapOf("filtered" to ModelState.SetState(setOf("aa")))
        model.eval(case.script.toScript()) shouldBe mapOf("filtered" to ModelState.SetState(setOf("cc")))
    }

    @Test
    fun `a script containing Observe events executes without error and matches the model`() {
        val observing = CaseScript(
            listOf(
                CaseStep.Op(sourceA, ScriptEvent.Add(writer, "aa")),
                CaseStep.Op(sourceA, ScriptEvent.Observe(writer)),
                CaseStep.Op(sourceA, ScriptEvent.Add(writer, "bbb")),
                CaseStep.Op(sourceA, ScriptEvent.Add(writer, "cc")),
                CaseStep.Op(sourceA, ScriptEvent.Observe(writer)),
                CaseStep.Op(sourceA, ScriptEvent.Remove(writer, "bbb")),
            ),
        )

        DifferentialRunner.run(filterCase(observing)) shouldBe RunOutcome.Success
    }
}
