package civictech.cell.link

import civictech.cell.data.delta.PnCounterDelta
import civictech.cell.data.delta.SetDelta
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * T05 finding 6: `sliceTo`'s `else -> delta` fallthrough shipped any
 * non-[Scoped] delta WHOLE across a partial-interest link — silently
 * breaking "a delta a peer has no interest in never crosses" (spec 40/42
 * §Interest-scoped instance sets) for a [civictech.cell.data.Replicable]
 * whose delta doesn't implement `Scoped` (`PnCounterDelta`, `WatermarkDelta`,
 * `CounterDelta`, `ListDelta`) yet can join a partial-interest mesh
 * (`PnCounterCell`, `WatermarkCell`). It now refuses instead: drops the
 * emission and counts/logs the refusal, exactly like a legitimate
 * `Scoped`-slices-to-nothing result.
 */
class SliceToRefusalTest {

    private val partialInterest = Interest.Slots(slots = setOf(0), totalSlots = 4)

    @Test
    fun `a non-Scoped delta against a partial interest is refused and counted, not shipped whole`() {
        val before = refusedSliceCount.get()
        val delta = PnCounterDelta(incs = mapOf(UUID.randomUUID() to 5L))

        val result = sliceTo(delta, partialInterest, keyOf = { it })

        result shouldBe null // refused, not shipped whole
        refusedSliceCount.get() shouldBe before + 1
    }

    @Test
    fun `a non-Scoped delta against Total interest still rides whole (the replication default, unaffected)`() {
        val before = refusedSliceCount.get()
        val delta = PnCounterDelta(incs = mapOf(UUID.randomUUID() to 5L))

        val result = sliceTo(delta, Interest.Total, keyOf = { it })

        result shouldBe delta
        refusedSliceCount.get() shouldBe before // no refusal — Total short-circuits before the Scoped check
    }

    @Test
    fun `a Scoped delta (SetDelta) against a partial interest goes through Scoped-within, never counted as a refusal`() {
        val before = refusedSliceCount.get()
        val delta = SetDelta(adds = mapOf("a" to emptySet(), "b" to emptySet(), "c" to emptySet()))

        sliceTo(delta, partialInterest, keyOf = { it })

        // Scoped.within decides the outcome (null or a genuine sub-slice) —
        // the point of this test is that the else-branch refusal path this
        // ticket closes is never reached for a Scoped delta.
        refusedSliceCount.get() shouldBe before
    }
}
