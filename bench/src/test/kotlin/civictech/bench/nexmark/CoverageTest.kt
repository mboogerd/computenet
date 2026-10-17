package civictech.bench.nexmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoverageTest {
    @Test
    fun `empty coverage exposes three not expressible and five unverified queries`() {
        val table = coverageFor(emptyList())
        assertEquals(8, table.rows.size)
        assertEquals(3, table.rows.count { it.status == Status.not_expressible })
        assertEquals(5, table.rows.count { it.status == Status.unverified })
        listOf(NexmarkQuery.Q4, NexmarkQuery.Q5, NexmarkQuery.Q6).forEach { query ->
            val row = table.rows.single { it.query == query }
            assertEquals(query.blockedCause, row.cause)
        }
        assertTrue(table.rows.all { !it.cause.isNullOrBlank() })
        assertTrue(table.render().lines().first() == "| Query | Tier | Status | Cause |")
    }

    @Test
    fun `coverage chooses the worst status and preserves its cause`() {
        val failed = NexmarkRecord.Failed(nexmarkTestHeader(), 3, listOf(RowDiff(listOf(1L), Side.ACTUAL_ONLY)))
        val unverified = NexmarkRecord.Unverified(nexmarkTestHeader(), "reference disabled")
        val row = coverageFor(listOf(unverified, failed)).rows.single { it.query == NexmarkQuery.Q1 }
        assertEquals(Status.failed, row.status)
        assertTrue(row.cause!!.contains("batch 3 failed"))
        assertTrue(row.cause.contains("differing rows"))

        val stateBound = NexmarkRecord.StateBound(nexmarkTestHeader(), "q7", 9L, 4)
        val boundRow = coverageFor(listOf(stateBound)).rows.single { it.query == NexmarkQuery.Q1 }
        assertEquals(Status.state_bound, boundRow.status)
        assertEquals("outlet q7 exceeded ceiling 9 at batch 4", boundRow.cause)
    }

    @Test
    fun `coverage table requires exactly one row per query and a cause for non verified rows`() {
        val rows = NexmarkQuery.entries.map { query ->
            CoverageRow(query, query.tier, Status.verified)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CoverageTable(rows.dropLast(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CoverageTable(rows.dropLast(1) + rows.first())
        }
        assertThrows(IllegalArgumentException::class.java) {
            CoverageTable(rows.map { row ->
                if (row.query == NexmarkQuery.Q1) row.copy(status = Status.failed) else row
            })
        }
    }
}
