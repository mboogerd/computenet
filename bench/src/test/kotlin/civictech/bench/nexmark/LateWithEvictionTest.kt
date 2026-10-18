package civictech.bench.nexmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LateWithEvictionTest {
    private val policy = LatenessPolicy(5_000)

    @Test
    fun `Q7 drops a bid injected after its window passed the floor`() {
        val events = windowedEvents()
        val late = LateEvents.lateBid(events, inWindowStart = 0L, id = Long.MAX_VALUE)
            .copy(price = Long.MAX_VALUE / 4L)
        val batches = LateEvents.inject(windowedBatches(events), at = 15, event = late)
        val policyResults = mutableListOf<NexmarkResult>()
        val withPolicy = runWindowed(
            query = NexmarkQuery.Q7,
            policy = policy,
            batches = batches,
            hooks = NexmarkRunHooks(resultObserved = { _, result -> policyResults += result }),
        )
        val reference = NexmarkReference.evaluate(NexmarkQuery.Q7, batches, policy)
        val accounting = requireNotNull(withPolicy.header.lateness)

        assertEquals(policy, withPolicy.header.policy)
        assertEquals(StateLabel.Evicting(5_000), withPolicy.header.stateLabel)
        assertTrue(accounting.droppedLate >= 1)
        assertEquals(reference.droppedLate, accounting.droppedLate)
        assertFalse(policyResults.last().rows.any { row -> row.first() == 0L })

        val withoutPolicy = finalResult(NexmarkQuery.Q7, batches, policy = null)
        assertNotEquals(policyResults.last().rows, withoutPolicy.rows)
        assertTrue(withoutPolicy.rows.any { row -> row[1] == late.id })
    }

    @Test
    fun `Q8 drops a late auction on the right and differs from no-policy evaluation`() {
        val events = windowedEvents()
        val person = events.filterIsInstance<Person>()
            .first { it.dateTime in 0L until NexmarkSpec.WINDOW_MS }
        val late = LateEvents.lateAuction(events, inWindowStart = 0L, id = Long.MAX_VALUE - 1L)
            .copy(seller = person.id, reserve = Long.MAX_VALUE / 5L)
        val batches = LateEvents.inject(windowedBatches(events), at = 15, event = late)
        val policyResults = mutableListOf<NexmarkResult>()
        val withPolicy = runWindowed(
            query = NexmarkQuery.Q8,
            policy = policy,
            batches = batches,
            hooks = NexmarkRunHooks(resultObserved = { _, result -> policyResults += result }),
        )
        val reference = NexmarkReference.evaluate(NexmarkQuery.Q8, batches, policy)
        val accounting = requireNotNull(withPolicy.header.lateness)
        val lateRow = listOf<Any>(person.id, person.name, late.reserve)

        assertEquals(policy, withPolicy.header.policy)
        assertEquals(StateLabel.Evicting(5_000), withPolicy.header.stateLabel)
        assertTrue(accounting.droppedLate >= 1)
        assertEquals(reference.droppedLate, accounting.droppedLate)
        assertFalse(lateRow in policyResults.last().rows)

        val withoutPolicy = finalResult(NexmarkQuery.Q8, batches, policy = null)
        assertNotEquals(policyResults.last().rows, withoutPolicy.rows)
        assertTrue(lateRow in withoutPolicy.rows)
    }

    private fun finalResult(
        query: NexmarkQuery,
        batches: List<Batch>,
        policy: LatenessPolicy?,
    ): NexmarkResult {
        val observed = mutableListOf<NexmarkResult>()
        runWindowed(
            query = query,
            policy = policy,
            batches = batches,
            hooks = NexmarkRunHooks(resultObserved = { _, result -> observed += result }),
        )
        return observed.last()
    }
}
