package civictech.oracle.gen

import civictech.cell.graph.ConnectStep
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.SpawnStep
import civictech.oracle.bind.CoreOperators
import civictech.oracle.bind.OperatorCatalog
import civictech.oracle.model.ScriptEvent
import civictech.oracle.model.SourceId
import civictech.oracle.model.WriterId
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

/**
 * [GeneratedCase] and its companions: serializability across a JVM boundary (D3), and
 * [GeneratedCase.controllerSeed] as a pure function of [GeneratedCase.seed] ([ORA1-GEN-07]).
 */
class GeneratedCaseTest {

    @BeforeEach
    fun register() {
        OperatorCatalog.reset()
        CoreOperators.registerAll()
    }

    @AfterEach
    fun emptyTheProcessWideCatalog() {
        OperatorCatalog.reset()
    }

    private fun buildCase(): GeneratedCase {
        val setEntry = OperatorCatalog.entry(CoreOperators.Ids.SET)!!
        val filterEntry = OperatorCatalog.entry(CoreOperators.Ids.FILTER)!!

        val spec = GraphSpec(
            listOf(
                SpawnStep(handle = "src", factory = setEntry.kernel),
                SpawnStep(handle = "f", factory = filterEntry.kernel),
                ConnectStep(from = "src", outlet = "outlet", to = "f", inlet = "inlet"),
            ),
        )

        val topology = CaseTopology(
            nodes = listOf(
                TopologyNode(handle = "src", catalogId = CoreOperators.Ids.SET, inputs = emptyList(), source = SourceId("src")),
                TopologyNode(handle = "f", catalogId = CoreOperators.Ids.FILTER, inputs = listOf("src"), source = null),
            ),
            terminals = listOf(TerminalSpec(name = "t", handle = "f")),
            placement = mapOf("src" to 0, "f" to 0),
        )

        val writer = WriterId("w1")
        val source = SourceId("src")
        val script = CaseScript(
            listOf(
                CaseStep.Op(source, ScriptEvent.Add(writer, "ab")),
                CaseStep.Op(source, ScriptEvent.Observe(writer)),
                CaseStep.Barrier,
                CaseStep.Op(source, ScriptEvent.Remove(writer, "ab")),
            ),
        )

        return GeneratedCase(
            seed = 42L,
            topology = topology,
            spec = spec,
            script = script,
            removeAudit = listOf(RemoveRecord(stepIndex = 3, observed = true)),
        )
    }

    /**
     * Structural equality of [GeneratedCase.topology], [GeneratedCase.script] and
     * [GeneratedCase.seed] — deliberately NOT of the whole [GeneratedCase] (and so not of
     * [GeneratedCase.spec]): `civictech.cell.graph.CellFactory` is a SAM `fun interface`, and a
     * deserialized lambda instance is never `equals` to the one that was serialized (no
     * generated lambda class overrides `equals`/`hashCode`), so a raw data-class `shouldBe`
     * across the round trip would fail on identity alone rather than on anything this round
     * trip is meant to prove. What has to survive the boundary intact is the data
     * [GeneratedCase] carries beside the spec.
     */
    @Test
    fun `a hand-built GeneratedCase round-trips through Java serialization with structural equality`() {
        val original = buildCase()

        val bytes = ByteArrayOutputStream().also { out ->
            ObjectOutputStream(out).use { it.writeObject(original) }
        }.toByteArray()

        val restored = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() } as GeneratedCase

        restored.seed shouldBe original.seed
        restored.topology shouldBe original.topology
        restored.script.steps shouldContainExactly original.script.steps
        restored.topology.terminals shouldContainExactly original.topology.terminals
        restored.spec.steps.size shouldBe original.spec.steps.size
    }

    @Test
    fun `controllerSeed is a pure function of seed alone`() {
        val a = buildCase()
        val b = buildCase().copy(seed = a.seed)

        a.controllerSeed shouldBe b.controllerSeed
    }

    @Test
    fun `controllerSeed differs for different seeds`() {
        val a = buildCase()
        val b = buildCase().copy(seed = a.seed + 1)

        (a.controllerSeed == b.controllerSeed) shouldBe false
    }

    /**
     * [CaseScript.toScript]'s projection: drops [CaseStep.Barrier], groups the remaining
     * [CaseStep.Op]s into one [civictech.oracle.model.SourceScript] per source, and preserves
     * each source's relative event order — checked as a per-source subsequence equality
     * against the original interleaved [CaseStep] list.
     */
    @Test
    fun `CaseScript toScript drops the barrier and groups ops by source preserving relative order`() {
        val sourceA = SourceId("a")
        val sourceB = SourceId("b")
        val writerA = WriterId("wa")
        val writerB = WriterId("wb")

        val aAdd1 = ScriptEvent.Add(writerA, "a1")
        val bAdd1 = ScriptEvent.Add(writerB, "b1")
        val aAdd2 = ScriptEvent.Add(writerA, "a2")
        val bRemove1 = ScriptEvent.Remove(writerB, "b1")

        val caseScript = CaseScript(
            listOf(
                CaseStep.Op(sourceA, aAdd1),
                CaseStep.Op(sourceB, bAdd1),
                CaseStep.Barrier,
                CaseStep.Op(sourceA, aAdd2),
                CaseStep.Op(sourceB, bRemove1),
            ),
        )

        val script = caseScript.toScript()

        script.sources() shouldContainExactly listOf(sourceA, sourceB)
        script.slice(sourceA).events shouldContainExactly listOf(aAdd1, aAdd2)
        script.slice(sourceB).events shouldContainExactly listOf(bAdd1, bRemove1)
    }
}
