package civictech.inspect.edit

import civictech.inspect.inspectorJson
import io.kotest.matchers.shouldBe
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

/**
 * `[WKB2-04]`, `[WKB2-42]`: [AuditRing] is the write plane's bounded audit
 * trail, built on the existing [civictech.inspect.RingBuffer] precedent
 * (`civictech.inspect.Activity`, `RingBufferTest`).
 */
class AuditRingTest {

    private fun record(id: String) = ApplyRecord(
        applyId = id,
        identity = "operator",
        submittedDraft = JsonPrimitive("draft-$id"),
        baseTopologyVersion = 1L,
        submittedAtMs = 0L,
    )

    @Test
    fun `an empty ring answers an empty list, never null and never a throw`() {
        AuditRing().entries() shouldBe emptyList()
    }

    @Test
    fun `past capacity, the oldest record is evicted and order is kept`() {
        val ring = AuditRing()

        (1..201).forEach { ring.record(record("a-$it")) }

        val entries = ring.entries()
        entries.size shouldBe 200
        entries.first().applyId shouldBe "a-2"
        entries.last().applyId shouldBe "a-201"
    }

    @Test
    fun `a fully populated record round-trips through inspectorJson unchanged`() {
        val original = ApplyRecord(
            applyId = "apply-1",
            identity = "operator",
            submittedDraft = JsonPrimitive("the-draft"),
            baseTopologyVersion = 42L,
            steps = mapOf(
                "step-1" to StepOutcome.Applied,
                "step-2" to StepOutcome.Failed("boom"),
                "step-3" to StepOutcome.Unwound,
                "step-4" to StepOutcome.NotRun,
            ),
            submittedAtMs = 100L,
            completedAtMs = null,
        )

        val encoded = inspectorJson.encodeToString(original)
        val decoded = inspectorJson.decodeFromString<ApplyRecord>(encoded)

        decoded shouldBe original
    }

    @Test
    fun `StepOutcome's discriminator values are exactly applied, failed, unwound, not-run`() {
        inspectorJson.encodeToString<StepOutcome>(StepOutcome.Applied) shouldBe """{"type":"applied"}"""
        inspectorJson.encodeToString<StepOutcome>(StepOutcome.Failed("x")) shouldBe
            """{"type":"failed","reason":"x"}"""
        inspectorJson.encodeToString<StepOutcome>(StepOutcome.Unwound) shouldBe """{"type":"unwound"}"""
        inspectorJson.encodeToString<StepOutcome>(StepOutcome.NotRun) shouldBe """{"type":"not-run"}"""
    }
}
