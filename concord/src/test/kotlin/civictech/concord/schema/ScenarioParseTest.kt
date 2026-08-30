package civictech.concord.schema

import civictech.concord.yaml.ConcordYaml
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * W0 acceptance: the four pilot scenarios round-trip through the schema types via
 * kaml — deserialize, re-serialize, and deserialize again to an equal [Scenario].
 * No execution, no driver: this only freezes that the schema can carry §3's shapes.
 */
class ScenarioParseTest {

    private val pilots = listOf(
        "corpus/24-data-cells/24-OP-UNION-01.yaml",
        "corpus/21-propagation/21-PIPE-01.yaml",
        "corpus/22-consistency/22-GF-DIAMOND-01.yaml",
        "corpus/controls/CTL-GOLDEN-01.yaml",
        // D-CONCORD (B2): the parser is lenient (unknown keys ignored), so a
        // round-trip is the only typo catch for a newly authored file.
        "corpus/24-data-cells/24-OP-COMBINE-02.yaml",
        "corpus/24-data-cells/24-REPLAY-01.yaml",
        // V1C-CONCORD: the three bounded-read scenarios, carrying the schema's
        // newest step verb and both new checks. Same reason as above — a
        // mistyped `limit:` or `cell:` would parse cleanly and silently do
        // nothing under the lenient parser, and this round-trip is the only
        // mechanism that catches it.
        "corpus/21-propagation/21-PULL-02.yaml",
        "corpus/24-data-cells/24-BOUND-01.yaml",
        "corpus/24-data-cells/24-BOUND-02.yaml",
        // D-C12: the re-baseline scenario, carrying the schema's newest step
        // verb. Same reason as above — a mistyped `restart` step would parse
        // cleanly under the lenient parser and silently not restart anything,
        // leaving a scenario that passes because both arms only ever saw the
        // post-restart adds.
        "corpus/21-propagation/21-REBASE-01.yaml",
        // computenet-yh6.1.8: the two duplicate-delivery scenarios, carrying the
        // schema's newest step verb. Same reason as above — a mistyped `value:` or
        // `inlet:` key is ignored by the lenient parser and silently defaults, so
        // the step would retransmit an empty payload (or at the wrong inlet) while
        // still reading as coverage of the live frontier.
        "corpus/15-durability/DUR-LIVE-01.yaml",
        "corpus/15-durability/DUR-CKPT-FRONTIER-01.yaml",
        // computenet-em9i: the contextless-drive scenario, carrying the schema's
        // newest step verb. Same reason again, and sharper here — the whole
        // content of a `drive-contextless` step is what it does NOT carry, so a
        // mistyped `on:` or `value:` is ignored by the lenient parser and the
        // step silently drives the default while the file still reads as
        // coverage of [24-DUR-06].
        "corpus/15-durability/DUR-CONTEXTLESS-01.yaml",
    )

    @TestFactory
    fun `every pilot scenario round-trips through the schema`(): List<DynamicTest> =
        pilots.map { path ->
            DynamicTest.dynamicTest(path) {
                val yaml = File(path).also { it.exists() shouldBe true }.readText()

                val decoded = ConcordYaml.instance.decodeFromString(Scenario.serializer(), yaml)
                decoded.id.isNotBlank() shouldBe true
                decoded.covers shouldNotBe emptyList<String>() // no orphan pilots (§1.5 lint)

                // Re-serialize, then decode again: structural equality is the round-trip guarantee.
                val reencoded = ConcordYaml.instance.encodeToString(Scenario.serializer(), decoded)
                val redecoded = ConcordYaml.instance.decodeFromString(Scenario.serializer(), reencoded)
                redecoded shouldBe decoded
            }
        }

    @TestFactory
    fun `pilot scenarios expose the expected shapes`(): List<DynamicTest> = listOf(
        DynamicTest.dynamicTest("24-OP-UNION-01 carries a full graph, script and checks") {
            val s = load("corpus/24-data-cells/24-OP-UNION-01.yaml")
            s.kind shouldBe Kind.EXAMPLE
            s.profile shouldBe Profile.CORE
            s.graph!!.cells.map { it.id } shouldContainExactly listOf("a", "b", "u", "v")
            s.script.filterIsInstance<ApplyStep>() shouldNotBe emptyList<ApplyStep>()
            (s.checks.first() as FinalView).view shouldBe "v"
        },
        DynamicTest.dynamicTest("22-GF-DIAMOND-01 carries a glitch-free quorum join and a set golden") {
            // Re-modeled R2-A (DISPUTES.md): the scalar `combine-latest` shape could
            // never be made glitch-free-observable (kernel gap — see the scenario's
            // own header note), so the pilot now carries a SET-based fork-join over
            // a real kernel glitch-free operator (`quorum-set`, `QuorumSetCell`).
            val s = load("corpus/22-consistency/22-GF-DIAMOND-01.yaml")
            s.graph!!.cells.single { it.type == "quorum-set" }.glitchFree shouldBe true
            s.script.filterIsInstance<ApplyStep>() shouldNotBe emptyList<ApplyStep>()
        },
        DynamicTest.dynamicTest("24-BOUND-02 carries a read-state limit sweep and both new checks") {
            // V1C-CONCORD: the sweep is explicit steps, not a generator — `kind:
            // generative` in this corpus means no graph, a generator: block and a
            // hardcoded check set, with no parameter sweep and no custom check.
            val s = load("corpus/24-data-cells/24-BOUND-02.yaml")
            s.kind shouldBe Kind.EXAMPLE
            s.script.filterIsInstance<ReadStateStep>().map { it.limit } shouldContainExactly listOf(1, 2, 4, 5, 6)
            s.script.filterIsInstance<ReadStateStep>().map { it.on }.toSet() shouldBe setOf("s")
            (s.checks.filterIsInstance<PagesEqualView>().single()).view shouldBe "v"
            (s.checks.filterIsInstance<WavePlaneUnchanged>().single()).cell shouldBe "s"
        },
        DynamicTest.dynamicTest("21-REBASE-01 carries a restart step between two quiesce barriers") {
            // D-C12: the restart must land AFTER the pre-restart adds have settled
            // and BEFORE the post-restart ones, or the scenario would not be
            // exercising a mid-stream recovery at all.
            val s = load("corpus/21-propagation/21-REBASE-01.yaml")
            s.script.filterIsInstance<RestartStep>().single().on shouldBe "s"
            val restartAt = s.script.indexOfFirst { it is RestartStep }
            (s.script.subList(0, restartAt).last() is QuiesceStep) shouldBe true
            (s.script[restartAt + 1] is QuiesceStep) shouldBe true
            (s.checks.filterIsInstance<ViewsConverge>().single()).views shouldContainExactly listOf("v", "x")
        },
        DynamicTest.dynamicTest("DUR-LIVE-01 retransmits a position an earlier add on the same source minted") {
            // computenet-yh6.1.8: the verb's whole point is that the position is
            // EXPLICIT, so what must be pinned is that the explicit position is the
            // one the script itself produced. `source:` names a cell the script
            // applies to, and each retransmitted counter is the index of an `add`
            // on that cell (1-based) — the mapping RetransmitBindingTest pins
            // behaviourally against the driver.
            val s = load("corpus/15-durability/DUR-LIVE-01.yaml")
            val adds = s.script.filterIsInstance<ApplyStep>().filter { it.op == "add" }
            val duplicates = s.script.filterIsInstance<RetransmitStep>()
            duplicates.map { it.counter } shouldContainExactly listOf(2L, 3L)
            duplicates.forEach { step ->
                step.on shouldBe "sink"
                step.source shouldBe "source"
                adds.map { it.on }.toSet() shouldBe setOf(step.source)
                // the duplicated payload is the element that add carried
                adds[(step.counter - 1).toInt()].value shouldBe step.value
            }
            // and the crash it follows is a real one: the duplicate is delivered
            // AFTER the durable host was crashed and recovered, or it would be
            // asserting nothing about a *restored* frontier.
            val crashAt = s.script.indexOfFirst { it is DespawnStep }
            (crashAt in 0 until s.script.indexOfFirst { it is RetransmitStep }) shouldBe true
        },
        DynamicTest.dynamicTest("DUR-CKPT-FRONTIER-01 puts its checkpoint last, with no journal tail after it") {
            // The construction is only discriminating if NOTHING is applied between
            // the checkpoint and the crash: a journal tail would carry its own
            // frontier records and restore the frontier all over again, which is
            // exactly why the third boundary was unobservable before this file.
            val s = load("corpus/15-durability/DUR-CKPT-FRONTIER-01.yaml")
            val checkpointAt = s.script.indexOfFirst { it is SnapshotStep }
            val crashAt = s.script.indexOfFirst { it is DespawnStep }
            (checkpointAt in 0 until crashAt) shouldBe true
            s.script.subList(checkpointAt, crashAt).filterIsInstance<ApplyStep>() shouldBe emptyList()
            s.script.filterIsInstance<RetransmitStep>().single().counter shouldBe 2L
        },
        DynamicTest.dynamicTest("CTL-GOLDEN-01 is a control") {
            val s = load("corpus/controls/CTL-GOLDEN-01.yaml")
            s.kind shouldBe Kind.CONTROL
        },
    )

    private fun load(path: String): Scenario =
        ConcordYaml.instance.decodeFromString(Scenario.serializer(), File(path).readText())
}
