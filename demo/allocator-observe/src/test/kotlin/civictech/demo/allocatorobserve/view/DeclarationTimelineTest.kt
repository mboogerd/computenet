package civictech.demo.allocatorobserve.view

import civictech.demo.allocatorobserve.declaration.AllocationDeclaration
import civictech.demo.allocatorobserve.declaration.DeclarationEvent
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

class DeclarationTimelineTest {

    private val decl6040 =
        AllocationDeclaration(weights = mapOf("computenet" to 60.0, "glass-factory" to 40.0), monthlyCapHours = 100.0, window = null)
    private val decl3070 =
        AllocationDeclaration(weights = mapOf("computenet" to 30.0, "glass-factory" to 70.0), monthlyCapHours = 100.0, window = null)

    private val t0 = Instant.parse("2026-04-01T00:00:00Z")
    private val t1 = Instant.parse("2026-04-01T01:00:00Z")
    private val t2 = Instant.parse("2026-04-01T02:00:00Z")

    @Test
    fun `inForceAt before the first event is null`() {
        val timeline = DeclarationTimeline(listOf(DeclarationEvent(t1, decl6040)))
        timeline.inForceAt(t0) shouldBe null
    }

    @Test
    fun `inForceAt at and after the first event returns its declaration`() {
        val timeline = DeclarationTimeline(listOf(DeclarationEvent(t0, decl6040)))
        timeline.inForceAt(t0) shouldBe decl6040
        timeline.inForceAt(t2) shouldBe decl6040
    }

    @Test
    fun `two events at the same instant resolve to the later in iteration order`() {
        // decl3070 appears LAST in the input Collection's iteration order, so it must win
        // from t0 onward, even though decl6040 shares the same observedAt.
        val timeline =
            DeclarationTimeline(
                listOf(
                    DeclarationEvent(t0, decl6040),
                    DeclarationEvent(t0, decl3070),
                ),
            )
        timeline.inForceAt(t0) shouldBe decl3070
        timeline.inForceAt(t1) shouldBe decl3070

        // Reversing the input iteration order flips which declaration wins,
        // proving the rule keys off iteration order and not e.g. weight content.
        val reversed =
            DeclarationTimeline(
                listOf(
                    DeclarationEvent(t0, decl3070),
                    DeclarationEvent(t0, decl6040),
                ),
            )
        reversed.inForceAt(t0) shouldBe decl6040
    }

    @Test
    fun `intervalsWithin clips to the requested range and covers each declaration`() {
        val timeline =
            DeclarationTimeline(
                listOf(
                    DeclarationEvent(t0, decl6040),
                    DeclarationEvent(t1, decl3070),
                ),
            )

        val intervals = timeline.intervalsWithin(t0, t2)

        intervals shouldBe
            listOf(
                DeclarationTimeline.DeclarationInterval(t0, t1, decl6040),
                DeclarationTimeline.DeclarationInterval(t1, t2, decl3070),
            )
    }

    @Test
    fun `intervalsWithin clips the first and last interval to the queried sub-range`() {
        val timeline =
            DeclarationTimeline(
                listOf(
                    DeclarationEvent(t0, decl6040),
                    DeclarationEvent(t1, decl3070),
                ),
            )
        val queryFrom = t0.plusSeconds(600)
        val queryTo = t1.plusSeconds(600)

        val intervals = timeline.intervalsWithin(queryFrom, queryTo)

        intervals shouldBe
            listOf(
                DeclarationTimeline.DeclarationInterval(queryFrom, t1, decl6040),
                DeclarationTimeline.DeclarationInterval(t1, queryTo, decl3070),
            )
    }

    @Test
    fun `intervalsWithin returns empty when no declaration covers the range`() {
        val timeline = DeclarationTimeline(listOf(DeclarationEvent(t1, decl6040)))
        timeline.intervalsWithin(t0, t1) shouldBe emptyList()
        timeline.intervalsWithin(t0, t0) shouldBe emptyList() // degenerate, from == to
    }

    @Test
    fun `intervalsWithin on an empty timeline is always empty`() {
        val timeline = DeclarationTimeline(emptyList())
        timeline.intervalsWithin(t0, t2) shouldBe emptyList()
    }

    @Test
    fun `uncoveredBefore reports the leading gap before the first declaration`() {
        val timeline = DeclarationTimeline(listOf(DeclarationEvent(t1, decl6040)))
        timeline.uncoveredBefore(t0, t2) shouldBe (t0 to t1)
    }

    @Test
    fun `uncoveredBefore is null once the range starts at or after the first declaration`() {
        val timeline = DeclarationTimeline(listOf(DeclarationEvent(t1, decl6040)))
        timeline.uncoveredBefore(t1, t2) shouldBe null
        timeline.uncoveredBefore(t2, t2.plusSeconds(1)) shouldBe null
    }

    @Test
    fun `uncoveredBefore on an empty timeline is the whole range`() {
        val timeline = DeclarationTimeline(emptyList())
        timeline.uncoveredBefore(t0, t2) shouldBe (t0 to t2)
    }

    @Test
    fun `uncoveredBefore clips its end to the queried range`() {
        val timeline = DeclarationTimeline(listOf(DeclarationEvent(t2, decl6040)))
        // First declaration starts after the queried range ends: the gap is
        // clipped to [from, to), not [from, firstObservedAt).
        timeline.uncoveredBefore(t0, t1) shouldBe (t0 to t1)
    }

    @Test
    fun `normalizedWeights of 60,40 is 0,6 and 0,4`() {
        decl6040.normalizedWeights()["computenet"]!! shouldBe (0.6 plusOrMinus 1e-9)
        decl6040.normalizedWeights()["glass-factory"]!! shouldBe (0.4 plusOrMinus 1e-9)
    }

    @Test
    fun `normalizedWeights of an all-zero map is empty`() {
        val allZero = AllocationDeclaration(weights = mapOf("computenet" to 0.0, "glass-factory" to 0.0), monthlyCapHours = 100.0, window = null)
        allZero.normalizedWeights() shouldBe emptyMap()
    }

    @Test
    fun `normalizedWeights of an empty map is empty`() {
        val empty = AllocationDeclaration(weights = emptyMap(), monthlyCapHours = 100.0, window = null)
        empty.normalizedWeights() shouldBe emptyMap()
    }
}
