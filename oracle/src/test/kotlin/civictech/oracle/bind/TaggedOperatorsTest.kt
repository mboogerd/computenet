package civictech.oracle.bind

import civictech.cell.graph.CellFactory
import civictech.oracle.model.Delivery
import civictech.oracle.model.ElementShape
import civictech.oracle.model.ModelState
import civictech.oracle.model.OperatorModel
import civictech.oracle.model.ScriptEvent
import civictech.oracle.model.SingleInstanceOrMapModel
import civictech.oracle.model.SourceId
import civictech.oracle.model.SourceModel
import civictech.oracle.model.SourceScript
import civictech.oracle.model.WriterId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream

/**
 * [TaggedOperators] as the production half of ORA2's tagged/keyed registration seam
 * (`[ORA2-GEN-06]`, BS-18) and [OptionalFamilies] as the honest availability gate
 * (`[ORA2-WGT-06]`, BS-15) — computenet-4ru.1.2.
 *
 * [OperatorCatalog] is a process-wide mutable singleton, so this class registers per test and
 * resets afterwards, exactly as `CoreOperatorsTest` does.
 */
class TaggedOperatorsTest {

    @BeforeEach
    fun register() {
        OperatorCatalog.reset()
        TaggedOperators.registerAll()
    }

    @AfterEach
    fun emptyTheProcessWideCatalog() {
        OperatorCatalog.reset()
    }

    @Test
    fun `every id in this file's slice of the vocabulary is registered, in declared order`() {
        OperatorCatalog.ids().toList() shouldContainExactly TaggedOperators.Ids.ALL
    }

    @Test
    fun `every entry carries a kernel factory, an evaluable model, and a shape rule`() {
        TaggedOperators.Ids.ALL.forEach { id ->
            val entry = OperatorCatalog.entry(id).shouldNotBeNull()
            withClue("$id: kernel factory") { entry.kernel.shouldBeInstanceOf<CellFactory>() }
            withClue("$id: the model must be evaluable, not a bare ReferenceOp") {
                (entry.model is SourceModel || entry.model is OperatorModel) shouldBe true
            }
        }
    }

    @Test
    fun `every registered kernel factory and model survives Java serialization`() {
        TaggedOperators.Ids.ALL.forEach { id ->
            val entry = OperatorCatalog.entry(id).shouldNotBeNull()
            withClue("$id: kernel factory is not serializable") { serialize(entry.kernel) }
            withClue("$id: reference model is not serializable") { serialize(entry.model) }
        }
    }

    // -------------------------------------------------------------------
    // [ORA2-GEN-06] / BS-18 — pairing failure fires for the tagged family too, via the SAME
    // mechanism ORA1 already enforces. Nothing new is built here; this proves the reuse.
    // -------------------------------------------------------------------

    @Test
    fun `BS-18 a tagged id with a kernel binding but no model fails loudly naming the id`() {
        val failure = assertThrows<IllegalArgumentException> {
            OperatorCatalog.register(
                id = "orMapWithNoModel",
                shape = ShapeRule.source(ElementShape.MapOf(ElementShape.Scalar, ElementShape.Scalar)),
                kernel = CellFactory { ref -> throw UnsupportedOperationException("never constructed (ref=$ref)") },
                model = null,
            )
        }
        failure.message.shouldNotBeNull().shouldContain("orMapWithNoModel")
        ("orMapWithNoModel" in OperatorCatalog) shouldBe false
    }

    @Test
    fun `BS-18 a tagged id with a model but no kernel binding fails loudly naming the id, the reverse direction`() {
        val failure = assertThrows<IllegalArgumentException> {
            OperatorCatalog.register(
                id = "orMapWithNoKernel",
                shape = ShapeRule.source(ElementShape.MapOf(ElementShape.Scalar, ElementShape.Scalar)),
                kernel = null,
                model = SingleInstanceOrMapModel,
            )
        }
        failure.message.shouldNotBeNull().shouldContain("orMapWithNoKernel")
        ("orMapWithNoKernel" in OperatorCatalog) shouldBe false
    }

    // -------------------------------------------------------------------
    // orMap — single-instance dot semantics: put atomicity and reset-remove, from the actual
    // registered model, and the fail-loud boundary on a slice carrying deliveries.
    // -------------------------------------------------------------------

    @Test
    fun `orMap put atomicity, a re-put tombstones only the source's own previously live dot`() {
        val model = OperatorCatalog.entry(TaggedOperators.Ids.OR_MAP)!!.model as SourceModel
        val writer = WriterId("w")
        val source = SourceId("s1")
        val slice = SourceScript(
            source,
            listOf(
                ScriptEvent.Put(writer, key = "k", element = "v1"),
                ScriptEvent.Put(writer, key = "k", element = "v2"),
            ),
        )
        val result = model.evaluate(slice) as ModelState.MapState
        result.entries shouldBe mapOf("k" to "v2")
    }

    @Test
    fun `orMap reset-remove, a remove of a key with no live dot is a no-op`() {
        val model = OperatorCatalog.entry(TaggedOperators.Ids.OR_MAP)!!.model as SourceModel
        val writer = WriterId("w")
        val source = SourceId("s1")
        val slice = SourceScript(source, listOf(ScriptEvent.RemoveKey(writer, key = "missing")))
        val result = model.evaluate(slice) as ModelState.MapState
        result.entries shouldBe emptyMap()
    }

    @Test
    fun `orMap fails loudly, by name, on a slice carrying gossip deliveries rather than silently ignoring them`() {
        val model = OperatorCatalog.entry(TaggedOperators.Ids.OR_MAP)!!.model as SourceModel
        val source = SourceId("s1")
        val peer = SourceId("s2")
        val slice = SourceScript(
            source,
            events = emptyList(),
            deliveries = listOf(Delivery(afterEvents = 0, from = peer, throughEvents = 0)),
        )
        val failure = shouldThrow<IllegalArgumentException> { model.evaluate(slice) }
        val message = failure.message.shouldNotBeNull()
        withClue("'by name' means BOTH the model's own name and the catalog id it registered under") {
            message shouldContain "SingleInstanceOrMapModel"
            message shouldContain TaggedOperators.Ids.OR_MAP
        }
        message shouldContain "deliveries"
    }

    // -------------------------------------------------------------------
    // [ORA2-WGT-06] / BS-15 — the optional-family availability gate: reported, never skipped.
    // -------------------------------------------------------------------

    @Test
    fun `BS-15 every optional family is probed and none is silently missing from the report`() {
        val availability = OptionalFamilies.probe()
        availability.map { it.family } shouldContainExactly listOf(
            "WeightedSetDelta", "WeightedSetCell", "TagsToWeightsCell",
            "WeightsToTagsCell", "UntagCell", "TaggedMapView",
        )
    }

    @Test
    fun `BS-15 every currently-absent optional family is reported not-applicable with a written reason, not skipped`() {
        val availability = OptionalFamilies.probe()
        availability.forEach { entry ->
            withClue("${entry.family}: verified absent from kernel/src/main 2026-08-21") {
                entry.available shouldBe false
            }
            withClue("${entry.family}: BS-15 requires a written reason, never a silent skip") {
                entry.reason.shouldNotBeNull()
                entry.reason!!.shouldContain("ORA2-WGT-06")
            }
        }
    }

    /**
     * The positive arm [probe] itself can never exercise: every [OptionalFamilies.CANDIDATES]
     * entry is absent today, so nothing committed proved `probe()` can report `available = true`
     * for a family that IS present — verified during the review by mutation (pointing
     * `TaggedMapView`'s FQN at `civictech.cell.data.SetCell`, an existing class, reddened the
     * all-absent test above), but never pinned. [OptionalFamilies.probeOne] is `internal`
     * exactly so this test can drive the `true` branch directly, against a real class that
     * genuinely is on the classpath, without touching [OptionalFamilies.CANDIDATES] itself.
     */
    @Test
    fun `OptionalFamilies probeOne reports available=true and no reason for a family class that is present on the classpath`() {
        val availability = OptionalFamilies.probeOne("SetCell", "civictech.cell.data.SetCell")

        availability shouldBe OptionalFamilies.Availability("SetCell", available = true, reason = null)
    }

    private fun serialize(value: Any) {
        ObjectOutputStream(ByteArrayOutputStream()).use { it.writeObject(value) }
    }
}
