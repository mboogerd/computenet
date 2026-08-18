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
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The differential halves of BS-7 (`[ORA1-DIFF-05]`), BS-9 and BS-4 (computenet-4ru.8.6).
 *
 * Hand-CONSTRUCTED [GeneratedCase] values throughout, per this feature's own established
 * discipline (`GeneratedCaseExecutionTest`'s KDoc): invoking the generator here would make
 * these tests track the generator's current choices instead of the runner's behavior.
 */
class LateJoinerAndPlacementTest {

    private val writer = WriterId("w")
    private val sourceA = SourceId("a")

    @BeforeEach
    fun registerCatalog() {
        CoreOperators.registerAll()
    }

    @AfterEach
    fun resetCatalog() {
        OperatorCatalog.reset()
    }

    private fun factory(id: String) = OperatorCatalog.entry(id)!!.kernel

    private fun spec(vararg steps: GraphStep) = GraphSpec(steps.toList())

    /**
     * `set(a) -> filter -> {early, late}` — one filter node read by two terminals: `early`
     * links at build time (as every non-late terminal does), `late` links only at the
     * script's [CaseStep.Barrier], after the graph has quiesced. Both name the SAME node
     * handle, so their expected states are identical in the model regardless of when the
     * kernel side links — the property under test is that the late-linked kernel fold
     * actually CATCHES UP to that same state rather than only seeing events after the link.
     */
    private fun lateJoinerCase(script: CaseScript, seed: Long = 4001L) = GeneratedCase(
        seed = seed,
        topology = CaseTopology(
            nodes = listOf(
                TopologyNode("src", CoreOperators.Ids.SET, emptyList(), sourceA),
                TopologyNode("flt", CoreOperators.Ids.FILTER, listOf("src"), null),
            ),
            terminals = listOf(
                TerminalSpec("early", "flt", late = false),
                TerminalSpec("late", "flt", late = true),
            ),
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

    /**
     * Ops BEFORE the barrier (so the late terminal must catch up on history it never
     * observed live), a [CaseStep.Barrier] (the late link point), more ops AFTER it (so the
     * late terminal must also keep tracking going forward), and a trailing [CaseStep.Barrier]
     * to force a final `onBarrier` snapshot — [DifferentialRunner.run]'s only observation
     * hook into live fold state.
     *
     * `CoreOperators.Ids.FILTER`'s registered predicate is `TEXT_LENGTH_IS_EVEN`
     * (`GeneratedCaseExecutionTest`), so even-length text survives: `aa`, `cc`, `dddd`.
     */
    private fun lateJoinerScript() = CaseScript(
        listOf(
            CaseStep.Op(sourceA, ScriptEvent.Add(writer, "aa")),
            CaseStep.Op(sourceA, ScriptEvent.Add(writer, "bbb")),
            CaseStep.Op(sourceA, ScriptEvent.Add(writer, "cc")),
            CaseStep.Op(sourceA, ScriptEvent.Remove(writer, "bbb")),
            CaseStep.Barrier,
            CaseStep.Op(sourceA, ScriptEvent.Add(writer, "dddd")),
            CaseStep.Op(sourceA, ScriptEvent.Add(writer, "e")),
            CaseStep.Barrier,
        ),
    )

    @Test
    fun `BS-7 late terminal catches up to early terminal and the model`() {
        val observations = mutableListOf<Map<String, ModelState>>()

        val outcome = DifferentialRunner.run(lateJoinerCase(lateJoinerScript())) { observations += it }

        outcome shouldBe RunOutcome.Success
        // Not vacuous: the shared filtered set really holds the surviving even-length text.
        val expected = ModelState.SetState(setOf("aa", "cc", "dddd"))

        // At the FIRST barrier (right after linking), the late terminal is at minimum present.
        observations.size shouldBe 2
        observations[0].keys shouldBe setOf("early", "late")

        // At the trailing (second) barrier, after the post-link ops and a full drain, late ==
        // early == the model's full-script result [24-CATCHUP-01]/[21-CATCHUP-02].
        val final = observations[1]
        final["early"] shouldBe expected
        final["late"] shouldBe expected
        final["late"] shouldBe final["early"]
    }
}
