package civictech.bench.nexmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NexmarkQueryTest {
    @Test
    fun `catalogue has eight entries with explicit blocked causes and deviations`() {
        assertEquals(listOf("Q1", "Q2", "Q3", "Q4", "Q5", "Q6", "Q7", "Q8"), NexmarkQuery.entries.map { it.name })
        NexmarkQuery.entries.forEach { assertTrue(it.deviations.isNotEmpty(), it.name) }

        listOf(NexmarkQuery.Q4, NexmarkQuery.Q5, NexmarkQuery.Q6).forEach { query ->
            assertEquals(Tier.NOT_EXPRESSIBLE, query.tier)
            assertNotNull(query.blockedCause)
            assertTrue(query.blockedCause!!.contains("aggregate output as a relation"))
        }
        assertTrue(NexmarkQuery.Q6.blockedCause!!.contains("Arrival-order aggregates"))
        assertEquals(Relation.BIDS, NexmarkQuery.Q1.drivingRelation)
        assertEquals(Relation.AUCTIONS, NexmarkQuery.Q3.drivingRelation)
        assertEquals(Relation.AUCTIONS, NexmarkQuery.Q8.drivingRelation)
    }
}
