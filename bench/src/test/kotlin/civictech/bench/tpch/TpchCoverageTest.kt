package civictech.bench.tpch

import civictech.bench.Drive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** BS-9's behavioural half: one SIM sweep leaves an explicit row for all 22 queries. */
class TpchCoverageTest {

    @Test
    fun `one SIM sweep has 13 hand verified and nine honestly blocked rows`() {
        val records = TpchQuery.entries
            .filter { it.tier == TpchTier.HAND_1 }
            .map { query ->
                TpchRun.run(
                    query = query,
                    seed = 7L,
                    sf = 0.001,
                    plan = RefreshPlan.alternating(seed = 7L, sf = 0.001, batches = 20),
                    drive = Drive.SIM,
                    env = tpchTestEnvironment(),
                )
            }

        val table = coverageFor(records)
        assertEquals(22, table.rows.size)
        assertEquals(13, table.rows.count { it.status == Status.verified })
        assertEquals(9, table.rows.count { it.status == Status.blocked })
        assertTrue(table.rows.all { !it.cause.isNullOrBlank() || it.status == Status.verified })

        val verified = table.rows.filter { it.status == Status.verified }
        assertTrue(verified.all { it.route == "hand" })

        val blocked = table.rows.filter { it.status == Status.blocked }
        assertTrue(blocked.all { it.cause!!.contains("[BB-TPCH-14]") })
        assertTrue(blocked.all { it.cause!!.contains("aggregate output as a relation") })
        assertTrue(blocked.all { it.cause!!.contains("GroupByCell emits MapDelta") })
        assertTrue(blocked.single { it.query == TpchQuery.Q21 }.cause!!.contains("EXISTS/NOT EXISTS"))
    }
}
