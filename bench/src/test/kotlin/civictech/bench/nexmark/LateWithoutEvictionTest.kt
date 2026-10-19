package civictech.bench.nexmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LateWithoutEvictionTest {
    @Test
    fun `a late higher Q7 bid updates an already-emitted window without a policy`() {
        val events = windowedEvents()
        val originalWinner = windowZeroWinner(events)
        val late = LateEvents.lateBid(events, inWindowStart = 0L, id = Long.MAX_VALUE)
            .copy(price = Math.addExact(originalWinner.price, 1L))
        val observed = mutableListOf<NexmarkResult>()
        val verified = runWindowed(
            query = NexmarkQuery.Q7,
            policy = null,
            batches = LateEvents.inject(windowedBatches(events), at = 15, event = late),
            hooks = NexmarkRunHooks(resultObserved = { _, result -> observed += result }),
        )

        assertEquals(StateLabel.UNBOUNDED_STATE, verified.header.stateLabel)
        assertNull(verified.header.lateness)
        assertEquals(late.id, windowZeroRow(observed.last())[1])
        assertEquals(late.price, windowZeroRow(observed.last())[4])
    }

    @Test
    fun `a late lower Q7 bid preserves the prior maximum without a policy`() {
        val events = windowedEvents()
        val originalWinner = windowZeroWinner(events)
        val late = LateEvents.lateBid(events, inWindowStart = 0L, id = Long.MAX_VALUE - 1L)
            .copy(price = 0L)
        val observed = mutableListOf<NexmarkResult>()
        val verified = runWindowed(
            query = NexmarkQuery.Q7,
            policy = null,
            batches = LateEvents.inject(windowedBatches(events), at = 15, event = late),
            hooks = NexmarkRunHooks(resultObserved = { _, result -> observed += result }),
        )

        assertEquals(StateLabel.UNBOUNDED_STATE, verified.header.stateLabel)
        assertNull(verified.header.lateness)
        assertEquals(originalWinner.id, windowZeroRow(observed.last())[1])
        assertEquals(originalWinner.price, windowZeroRow(observed.last())[4])
    }

    private fun windowZeroWinner(events: List<Event>): Bid = events.filterIsInstance<Bid>()
        .filter { bid -> bid.dateTime in 0L until NexmarkSpec.WINDOW_MS }
        .minWith(compareByDescending<Bid> { it.price }.thenBy { it.id })

    private fun windowZeroRow(result: NexmarkResult): List<Any> =
        result.rows.single { row -> row.first() == 0L }
}
