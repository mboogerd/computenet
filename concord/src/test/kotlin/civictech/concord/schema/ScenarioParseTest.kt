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
        DynamicTest.dynamicTest("22-GF-DIAMOND-01 carries a glitch-free combine and a value golden") {
            val s = load("corpus/22-consistency/22-GF-DIAMOND-01.yaml")
            s.graph!!.cells.single { it.type == "combine-latest" }.glitchFree shouldBe true
            s.script.filterIsInstance<ApplyStep>().single().times shouldBe 50
        },
        DynamicTest.dynamicTest("CTL-GOLDEN-01 is a control") {
            val s = load("corpus/controls/CTL-GOLDEN-01.yaml")
            s.kind shouldBe Kind.CONTROL
        },
    )

    private fun load(path: String): Scenario =
        ConcordYaml.instance.decodeFromString(Scenario.serializer(), File(path).readText())
}
